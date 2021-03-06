package gem

import gem.config._
import gem.enum.SmartGcalType

import scalaz.Functor

sealed abstract class Step[A] extends Product with Serializable {
  def instrument: A
}

final case class BiasStep     [A](instrument: A)                               extends Step[A]
final case class DarkStep     [A](instrument: A)                               extends Step[A]
final case class GcalStep     [A](instrument: A, gcal:      GcalConfig)        extends Step[A]
final case class ScienceStep  [A](instrument: A, telescope: TelescopeConfig)   extends Step[A]
final case class SmartGcalStep[A](instrument: A, smartGcalType: SmartGcalType) extends Step[A]

object Step {
  implicit val FunctorStep: Functor[Step] = new Functor[Step] {
    def map[A, B](fa: Step[A])(f: A => B): Step[B] =
      fa match {
        case BiasStep(a)         => BiasStep(f(a))
        case DarkStep(a)         => DarkStep(f(a))
        case GcalStep(a, g)      => GcalStep(f(a), g)
        case ScienceStep(a, t)   => ScienceStep(f(a), t)
        case SmartGcalStep(a, s) => SmartGcalStep(f(a), s)
      }
  }
}