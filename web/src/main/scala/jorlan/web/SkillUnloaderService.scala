package jorlan.web

import japgolly.scalajs.react.Callback
import org.scalajs.dom

import scala.scalajs.js

object SkillUnloaderService {

  // TODO ptu this in the skill screen
//  def triggerUnload(props: Props): Callback = {
//    // Transition state back to Unloaded
//    $.modState(_.copy(status = Unloaded)) >>
//      // Perform file and runtime memory teardown
//      SkillUnloaderService.unloadSkill(props.skillId)
//  }
//
  /** Disconnects a dynamic skill from the application memory and DOM workspace.
    */
  def unloadSkill(skillId: String): Callback =
    Callback {
      // 1. Clear application state map references
      SkillRegistry.purgeSkillRecord(skillId)

      // 2. Remove script element from DOM to stop further script thread instantiation
      val scriptElement = dom.document.getElementById(s"script-$skillId")
      if (scriptElement != null && dom.document.body.contains(scriptElement)) {
        dom.document.body.removeChild(scriptElement)
      }

      // 3. Clear any global namespace pollutants left by the script bundle evaluation
      // Cast the global window context to a dictionary to safely delete keys
      val globalWindow = dom.window.asInstanceOf[js.Dictionary[js.Any]]

      if (globalWindow.contains(skillId)) {
        globalWindow.remove(skillId) // Safe dictionary-based deletion in Scala.js
      }

      // Force a minor collection hint where supported
      System.gc()
    }

}
