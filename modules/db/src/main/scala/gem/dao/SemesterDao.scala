package gem
package dao

import edu.gemini.spModel.core._

import doobie.imports._

import scalaz._, Scalaz._

object SemesterDao {

  implicit val SemesterHalfMeta: Meta[Semester.Half] =
    Meta[String].nxmap(Semester.Half.valueOf, _.name)

  implicit val SemesterMeta: Meta[Semester] =
    Meta[String].nxmap(Semester.parse, _.toString)

  def canonicalize(s: Semester): ConnectionIO[Semester] =
    sql"""
      INSERT INTO semester (semester_id, year, half)
      VALUES (${s.toString}, ${s.getYear}, ${s.getHalf})
      ON CONFLICT DO NOTHING
    """.update.run.as(s)

}