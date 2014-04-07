package controllers

import play.api.mvc._
import lib._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import play.api.libs.ws.WS
import org.kohsuke.github.GitHub
import play.api.libs.json.JsString
import scala.Some
import collection.convert.wrapAsScala._
import play.api.libs.json.JsString
import scala.Some
import play.api.Logger
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.Form
import scala.util.Try
import java.io.IOException


object Application extends Controller {

  def audit(orgName: String, apiKey: String) = Action.async {
    val auditDef = AuditDef.safelyCreateFor(orgName, apiKey)

    Logger.info(s"Asked to audit ${auditDef.orgLogin} seemLegit=${auditDef.seemsLegit}")

    if (auditDef.seemsLegit) {
      for (orgSnapshot <- OrgSnapshot(auditDef)) yield {
        Logger.info(s"availableRequirementEvaluators=${orgSnapshot.availableRequirementEvaluators} ${orgSnapshot.orgUserProblemStats}")
        orgSnapshot.createIssuesForNewProblemUsers()

        orgSnapshot.updateExistingAssignedIssues()

        orgSnapshot.closeUnassignedIssues()
        
        Ok
      }
    } else future { NotAcceptable }
  }

  import GithubAppConfig._
  val ghAuthUrl = s"${authUrl}?client_id=${clientId}&scope=${scope}"
  def index = Action { implicit req =>
    Ok(views.html.userPages.index(ghAuthUrl, apiKeyForm))
  }

  val apiKeyForm = Form("apiKey" -> of[String])

  def oauthCallback(code: String) = Action.async {
    import GithubAppConfig._
    val resFT = WS.url(accessTokenUrl)
                  .withQueryString(("code", code),("client_id", clientId),("client_secret", clientSecret))
                  .withHeaders(("Accept", "application/json"))
                  .post("")

      resFT.map{ res =>
        res.json \ "access_token" match {
          case JsString(accessCode) => Redirect("/choose-your-org").withSession("userId" -> accessCode)
          case _ => Redirect("/")
        }
    }
  }

  def storeApiKey() = Action { implicit req =>
   apiKeyForm.bindFromRequest().fold(
     formWithErrors => Redirect("/"),
     accessToken => {
       try {
         GitHub.connectUsingOAuth(accessToken)
         Redirect("/choose-your-org").withSession("userId" -> accessToken)
       }
       catch {
         case e: IOException => {
           Redirect("/")
         }
       }
     }
   )
  }

  def chooseYourOrg = Action { implicit req =>
    req.session.get("userId") match {
      case Some(accessToken) => {
        val conn = GitHub.connectUsingOAuth(accessToken)
        val orgs = conn.getMyOrganizations().values().toList
        val user = conn.getMyself
        Ok(views.html.userPages.orgs(orgs, user, accessToken))
      }
      case None => Ok(views.html.userPages.index(ghAuthUrl, apiKeyForm))
    }
  }
}
