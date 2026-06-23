/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.tui

/** All known shell command prefixes for Tab autocomplete. Sorted alphabetically. */
object CommandCompletions {

  val commands: Vector[String] = Vector(
    "/about",
    "/agents list",
    "/agents stop ",
    "/approvals approve ",
    "/approvals deny ",
    "/approvals list",
    "/calendar list",
    "/calendar today",
    "/capabilities",
    "/commands",
    "/contacts find ",
    "/email list",
    "/email read ",
    "/email search ",
    "/exit",
    "/help",
    "/memory checkpoint",
    "/memory forget ",
    "/memory list",
    "/memory list Shared",
    "/memory list User",
    "/memory list Workspace",
    "/memory policy",
    "/memory policy interval ",
    "/memory policy off-session-end",
    "/memory policy off-user-request",
    "/memory policy on-before-effect",
    "/memory policy on-session-end",
    "/memory policy on-user-request",
    "/memory privatize ",
    "/memory remember ",
    "/memory search ",
    "/memory share ",
    "/model",
    "/models",
    "/new",
    "/oauth connect ",
    "/oauth list",
    "/oauth revoke ",
    "/oauth status ",
    "/personality",
    "/personality set formality ",
    "/personality set languages ",
    "/personality set name ",
    "/personality set prompt ",
    "/quit",
    "/roles create ",
    "/roles list",
    "/scheduler list",
    "/scheduler result ",
    "/skills",
    "/skills disable ",
    "/skills enable ",
    "/skills config get ",
    "/skills config set ",
    "/status",
    "/trace",
    "/trace debug",
    "/trace error",
    "/trace info",
    "/trace none",
    "/trace warning",
    "/users assign-role ",
    "/users capabilities ",
    "/users create ",
    "/users deactivate ",
    "/users grant ",
    "/users identities ",
    "/users link-identity ",
    "/users list",
    "/users list all",
    "/users list inactive",
    "/users revoke-grant ",
    "/users revoke-role ",
    "/users roles ",
    "/users unlink-identity ",
    "/users update ",
    "/whoami",
  ).sorted

  /** Return all commands that start with `prefix`. */
  def completions(prefix: String): Vector[String] =
    commands.filter(_.startsWith(prefix))

}
