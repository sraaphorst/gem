package gem
package telnetd
package command

import gem.enum.ProgramRole
import net.bmjames.opts.types._
import tuco._, Tuco._

/** A command to show information about the current user. */
object whoami {

  val command: GemCommand =
    shellCommand[User[ProgramRole]](
      "whoami", "Show information about the current user.",
      Parser.pure { u =>
        for {
          _ <- writeLn(s"username: ${u.id}")
          _ <- writeLn(s"   first: ${u.firstName}")
          _ <- writeLn(s"    last: ${u.lastName}")
          _ <- writeLn(s"   email: ${u.email}")
          _ <- writeLn(s"   flags: ${if (u.isStaff) "staff" else "<none>"}")
        } yield u
      }
    ).zoom(Session.L.data[GemState] >=> GemState.L.user)

}
