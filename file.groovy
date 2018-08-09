/*
* Created by Pushpa Bohra
*/
import com.mphrx.base.BaseService
import com.mphrx.dicr.JobConfiguration
import org.apache.log4j.Logger
import org.codehaus.groovy.grails.commons.GrailsApplication
import com.mongodb.*
import consus.basetypes.CodeableConcept
import consus.basetypes.Identifier
import consus.primitiveTypes.Extension
import consus.primitiveTypes.StringType
import consus.primitiveTypes.BooleanType
import com.mphrx.dto.DataMessageDTO
import com.mphrx.dicr.AdministrativeResourceCrudService
import com.mphrx.commons.consus.MongoService
import consus.constants.StringConstants
import consus.basetypes.Coding
import com.mphrx.auth.User
import com.mphrx.consus.hl7.Hl7Message
import consus.hl7.Hl7MessageType
import consus.resources.PatientResource
import com.mphrx.notification.Notification
import com.mphrx.notification.NotificationDeliveryStatus
import com.mphrx.notification.NotificationType
import com.mphrx.dicr.DicrConfig
import java.text.SimpleDateFormat
import java.util.Calendar

public class NotifyUserOnDeactivationService extends BaseService {

    public static Logger log = Logger.getLogger("com.mphrx.NotifyUserOnDeactivationService")
    GrailsApplication grailsApplication

    Map customJobFrequencyMap = [ "FIVE_SECONDS":5,
                                  "TEN_SECONDS":10,
                                  "THIRTY_SECONDS":30,
                                  "ONE_MIN":60,
                                  "TWO_MIN":120,
                                  "THREE_MIN":180,
                                  "FIVE_MIN":300,
                                  "TEN_MIN":600,
                                  "FIFTEEN_MIN":900,
                                  "HALF_HOUR":1800,
                                  "ONE_HOUR":3600,
                                  "TWO_HOURS":7200,
                                  "FOUR_HOURS":14400,
                                  "EIGHT_HOURS":28800,
                                  "TWELVE_HOURS":43200,
                                  "EVERY_MIDNIGHT":86400];

    @Override
    def executeService(JobConfiguration jobConfiguration) {

        log.info("############ : Job invoked time: ${new Date()} JobConfigurationID [${jobConfiguration.id}] instance [${jobConfiguration.modInstance}] : ############")

        serviceName = "notifyUserOnDeactivation"

        if (!changeCurrentJobForMultipleInstance(1, jobConfiguration.modInstance)) {
            log.info("############ : Could not start process. Previous instance already running : ############")
            return
        }

        try {
            checkUserUpdateOnDEACT()
            log.info("############ : Job ended time: ${new Date()} JobConfigurationID [${jobConfiguration.id}] instance [${jobConfiguration.modInstance}] : ############")
        } catch (Exception ex) {
            log.error("############ : Error: Exception occurred to process ${serviceName} custom job : ############", ex)
        } finally {
            changeCurrentJobForMultipleInstance(-1, jobConfiguration.modInstance)
        }
    }

    public void checkUserUpdateOnDEACT()
    {
        JobConfiguration jobConfigHours = JobConfiguration.findByServiceName("notifyUserOnDeactivation")
        String jobFrequency =  ""
        if (jobConfigHours) {
            jobFrequency = jobConfigHours.frequency;
            log.info " Job Frequency of checkUserActivation is  [${jobFrequency}]";
        }

        Date endTime= new Date()
        Date startTime = new Date(System.currentTimeMillis() - (customJobFrequencyMap.get(jobFrequency)*1000))

        log.info("Starting Time for User/hl7 search : "+startTime)
        log.info("Ending Time for User/hl7 search : "+endTime)

        def userCriteria = User.createCriteria()
        def userResults = userCriteria.list {
            between("lastUpdated", startTime, endTime)
            eq("enabled",false)
        }

        Calendar cal = Calendar.getInstance()
        cal.setTime(startTime)

        cal.add(Calendar.HOUR, - cal.get(Calendar.HOUR))
        cal.add(Calendar.MINUTE, - cal.get(Calendar.MINUTE))
        cal.add(Calendar.SECOND, - cal.get(Calendar.SECOND))

        startTime = cal.getTime()

        log.info("startTime for notification : ${startTime}")
        log.info("endTime for notification : ${endTime}")

        userResults.each{it ->
            User user = User.findById(it)

            def notificationCriteria = Notification.createCriteria()
            def notificationResults = notificationCriteria.list {
                between("sentDate",startTime, endTime)
                eq("status", "SENT")
                eq("subject", "Compte utilisateur désactivé")
                eq("toUser",user._id)
            }
            log.info("notificationResults.size() : ${notificationResults.size()}")
            if(notificationResults.size() > 0) {
                log.info("Notifictaion already sent to user id : ${it}")
            }
            else {
                log.info("Sending notification to user id : ${it}")
                createNotification(user)
            }
        }
    }

    def createNotification(User user) {
        log.info("Checking for user email.")
        if (user?.email && user?.email != "" && user?.email != null) {

            log.info("Creating notification to user with email : ${user?.email}")
            Notification noti = new Notification()
            noti.dateCreated = new Date()
            noti.failedRetries = 0
            noti.notificationType = "email"
            noti.overrideUserPreference = false
            noti.priority = "INFO_MSG"
            noti.read = false
            noti.recipientType = "REGISTERED_USER"
            noti.status = NotificationDeliveryStatus.PENDING
            noti.toUser = user
            //noti.toEmail = user?.email
            noti.toEmail = "patientportailagfa@gmail.com"
            noti.subject = "Compte utilisateur désactivé"
            String mailFooter = "<table style='width:600pt;'><tr><td style='width:70pt;'><img width=200 height=100 src='https://patient.imageriemedicalerambot.com/themes/assets/images/Logo.png' alt='Rambot Logo'></td></tr></table>"
            String message = "<div>Chèr(e) ${user?.firstName},<br/><br/>Votre compte ${user?.email} d’accès à notre portail patient a été désactivé, vos résultats sont disponibles sur la nouvelle adresse mail que vous nous avez communiqué.<br/><br/>Si vous rencontrez des difficultés, n’hésitez pas à nous contacter uniquement par email à l’adresse suivante <a href= 'contact@imageriemedicalerambot.fr'>contact@imageriemedicalerambot.fr</a><br/><br/>Cordialement<br/><br/></div>" + mailFooter
            noti.message = message
			
			log.info("noti.subject : ${noti.subject}")
			log.info("noti.message : ${noti.message}")

            if (!noti.save(flush: true)) {
                noti.errors.allErrors.each {
                    log.error "Error storing Notification"
                    return null;
                }
            } else {
                log.info("Notification stored successfully.")
            }
            log.info("Notification Id: ${noti.id}")
        } else{
            log.info("Can't created notification to user with _id : ${user?.id} because no email id is found.")
        }
    }
}
