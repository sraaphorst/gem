package gem

import gem.dao._
import gem.enum._
import gem.config._

import edu.gemini.pot.sp.{ISPProgram, ISPObservation}
import edu.gemini.spModel.core._
import edu.gemini.spModel.config.ConfigBridge
import edu.gemini.spModel.config.map.ConfigValMapInstances.IDENTITY_MAP;

import java.io._
import java.{ util => JU }
import java.util.logging.{ Logger, Level }
import java.time.{Duration, Instant}

import scala.collection.JavaConverters._

import scalaz._, Scalaz._
import scalaz.effect._
import scalaz.concurrent.Task

import doobie.imports._

object Importer extends SafeApp {
  // import ConfigSyntax._
  import ConfigReader._

  val dir = new File("archive")

  val xa = DriverManagerTransactor[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:gem",
    "postgres",
    ""
  )

  val lxa = DriverManagerTransactor[Task](
    "org.postgresql.Driver",
    "jdbc:postgresql:gem",
    "postgres",
    ""
  )

  def steps(o: ISPObservation): List[Map[String, Object]] =
    ConfigBridge
      .extractSequence(o, new JU.HashMap, IDENTITY_MAP)
      .getAllSteps
      .toList
      .map { c =>
         c.itemEntries
          .iterator
          .map { e => (e.getKey.toString, e.getItemValue) }
          .toMap
      }

  def insert(p: ISPProgram): IO[Unit] = {
    val sid = p.getProgramID.toString
    val pid = Program.Id.parse(sid)
    val tit = p.getDataObject.getTitle
    val obs = p.getAllObservations.asScala.toList

    val ins: ConnectionIO[Unit] =
      ProgramDao.insert(Program(pid, tit, Nil)) *>
      obs.traverse_ { o =>

        val ss   = steps(o)
        val inst = ss.flatMap(_.cget(Legacy.Instrument.Instrument)).distinct match {
          case      Nil => None
          case o :: Nil => Some(o)
          case o :: os  => sys.error("More than one instrument in sequence: " + (o :: os))
        }

        val oid = o.getObservationID
        val newObs = Observation(
          Observation.Id(pid, o.getObservationNumber),
          o.getDataObject.getTitle,
          inst,
          Nil
        )

        import edu.gemini.spModel.dataset.{Dataset => OldDataset}
        import edu.gemini.spModel.obslog.ObsLog

        val configs = ss.flatMap(unsafeFromConfig)
        val obsLog  = Option(ObsLog.getIfExists(o)).fold(Map.empty[Int, OldDataset]) {
                        _.getAllDatasetRecords.asScala.map(_.exec.dataset).map(d => d.getIndex -> d).toMap
                      }

        ObservationDao.insert(newObs) *>
        configs.zipWithIndex.traverseU { case (c, n) =>
          for {
            id <- StepDao.insert(newObs.id, Location.unsafeMiddle(n * 100), c)
            _  <- obsLog.get(n).foldMap { oldDataset =>
                    val lab = Dataset.Label.unsafeFromString(oldDataset.getLabel.toString)
                    val tim = Instant.ofEpochMilli(oldDataset.getTimestamp)
                    val dst = Dataset(lab, oldDataset.getDhsFilename, tim)
                    DatasetDao.insert(id, dst).void
                  }
          } yield id
        }.void

      }

    ins.transact(xa)
  }

  def readAndInsert(u: User[_], r: ProgramReader, f: File, log: Log[IO]): IO[Unit] =
    log.log(u, s"read ${f.getName}")(r.read(f)).flatMap {
      case Some(p) => log.log(u, s"insert ${p.getProgramID}")(insert(p))
      case None    => IO.ioUnit
    } .except(e => IO(e.printStackTrace))

  def xmlFiles(dir: File, num: Int): IO[List[File]] =
    IO(dir.listFiles.toList.filter(_.getName.toLowerCase.endsWith(".xml"))).map(_.take(num))

  def readAndInsertAll(u: User[_], r: ProgramReader, dir: File, num: Int, log: Log[IO]): IO[Unit] =
    xmlFiles(dir, num).flatMap(_.traverse_(readAndInsert(u, r, _, log)))

  val configLogging: IO[Unit] =
    IO(List(
      "edu.gemini.spModel.type.SpTypeUtil"
    ).map(Logger.getLogger).foreach(_.setLevel(Level.OFF)))

  val clean: ConnectionIO[Unit] =
    for {
      _ <- sql"truncate program cascade".update.run
      _ <- sql"truncate log".update.run
      _ <- sql"delete from semester".update.run
    } yield ()

  val checkArchive: IO[Unit] =
    IO(dir.isDirectory).flatMap { b =>
      b.unlessM(IO(sys.error("""
        |
        |** Root of project needs an archive/ dir with program xml files in it.
        |** Try ln -s /path/to/some/stuff archive
        |""".stripMargin)))
    }

  override def runl(args: List[String]): IO[Unit] =
    for {
      u <- UserDao.selectRoot.transact(xa)
      l <- Log.newLog[IO]("importer", lxa)
      n <- IO(args.headOption.map(_.toInt).getOrElse(Int.MaxValue))
      _ <- checkArchive
      _ <- configLogging
      _ <- clean.transact(xa)
      _ <- ProgramReader.using(readAndInsertAll(u, _, dir, n, l))
      _ <- l.shutdown(5 * 1000) // if we're not done soon something is wrong
      _ <- IO.putStrLn("Done.")
    } yield ()


  def unsafeFromConfig(config: Map[String, Object]): Option[Step[InstrumentConfig]] = {

    val observeType = config.cget(Legacy.Observe.ObserveType)
    val instrument  = config.cget(Legacy.Instrument.Instrument).map(unsafeInstConfig(_, config))

    (observeType |@| instrument).tupled.collect {

      case ("BIAS",   i) =>
        BiasStep(i)

      case ("DARK",   i) =>
        DarkStep(i)

      case ("OBJECT" | "CAL", i) =>
        val p = config.cgetOrElse(Legacy.Telescope.P, OffsetP.Zero)
        val q = config.cgetOrElse(Legacy.Telescope.Q, OffsetQ.Zero)
        ScienceStep(i, TelescopeConfig(p,q))

      case ("ARC" | "FLAT", i) =>
        val l = config.uget(Legacy.Calibration.Lamp)
        val f = config.uget(Legacy.Calibration.Filter)
        val d = config.uget(Legacy.Calibration.Diffuser)
        val s = config.uget(Legacy.Calibration.Shutter)
        val e = config.uget(Legacy.Calibration.ExposureTime)
        val c = config.uget(Legacy.Calibration.Coadds)
        GcalStep(i, GcalConfig(l, f, d, s, e, c))

      case x =>
        sys.error("Unknown observeType: " + x + config.mkString("\n>  ", "\n>  ", ""))

    }

  }

  def unsafeInstConfig(i: Instrument, config: Map[String, Object]): InstrumentConfig =
    i match {
      case Instrument.Flamingos2 =>

        val fpu           = config.uget(Legacy.Instrument.F2.Fpu)
        val mosPreimaging = config.uget(Legacy.Instrument.MosPreImaging)
        val exposureTime  = config.cgetOrElse(Legacy.Observe.ExposureTime, Duration.ofSeconds(0))
        val filter        = config.uget(Legacy.Instrument.F2.Filter)
        val lyoutWheel    = config.uget(Legacy.Instrument.F2.LyotWheel)
        val disperser     = config.uget(Legacy.Instrument.F2.Disperser)
        val windowCover   = config.cgetOrElse(Legacy.Instrument.F2.WindowCover, F2WindowCover.Open)
        F2Config(fpu, mosPreimaging, exposureTime, filter, lyoutWheel, disperser, windowCover)

      case _ => GenericConfig(i)
    }

}
