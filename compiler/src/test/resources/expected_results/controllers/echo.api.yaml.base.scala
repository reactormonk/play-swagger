package echo

import play.api.mvc.{Action, Controller, Results}
import play.api.http.Writeable
import Results.Status
import de.zalando.play.controllers.{PlayBodyParsing, ParsingError}
import PlayBodyParsing._
import scala.util._




trait HandlerBase extends Controller with PlayBodyParsing {
    private val methodResponseMimeType    = "application/json"
    private val methodActionSuccessStatus = Status(200)

    private type methodActionRequestType       = (Unit)
    private type methodActionResultType        = Null
    private type methodActionType              = methodActionRequestType => Try[methodActionResultType]

    private val errorToStatusmethod: PartialFunction[Throwable, Status] = PartialFunction.empty[Throwable, Status]

    def methodAction = (f: methodActionType) => Action {        
            val result = processValidmethodRequest(f)()                
            result
    }

    private def processValidmethodRequest(f: methodActionType)(request: methodActionRequestType) = {
        implicit val methodWritableJson = anyToWritable[methodActionResultType](methodResponseMimeType)
        val callerResult = f(request)
        val status = callerResult match {
            case Failure(error) => (errorToStatusmethod orElse defaultErrorMapping)(error)
            case Success(result) => methodActionSuccessStatus(result)
        }
        status
    }
}


trait EchoApiYamlBase extends Controller with PlayBodyParsing {
    private val postResponseMimeType    = "application/json"
    private val postActionSuccessStatus = Status(200)

    private type postActionRequestType       = (PostName, PostName)
    private type postActionResultType        = PostResponses200
    private type postActionType              = postActionRequestType => Try[postActionResultType]

    private val errorToStatuspost: PartialFunction[Throwable, Status] = PartialFunction.empty[Throwable, Status]

    def postAction = (f: postActionType) => (name: PostName, year: PostName) => Action {        
            val result =                
                    new PostValidator(name, year).errors match {
                        case e if e.isEmpty => processValidpostRequest(f)((name, year))
                        case l =>
                            implicit val marshaller: Writeable[Seq[ParsingError]] = parsingErrors2Writable(postResponseMimeType)
                            BadRequest(l)
                    }
                
            result
    }

    private def processValidpostRequest(f: postActionType)(request: postActionRequestType) = {
        implicit val postWritableJson = anyToWritable[postActionResultType](postResponseMimeType)
        val callerResult = f(request)
        val status = callerResult match {
            case Failure(error) => (errorToStatuspost orElse defaultErrorMapping)(error)
            case Success(result) => postActionSuccessStatus(result)
        }
        status
    }
    private val gettest_pathByIdResponseMimeType    = "application/json"
    private val gettest_pathByIdActionSuccessStatus = Status(200)

    private type gettest_pathByIdActionRequestType       = (String)
    private type gettest_pathByIdActionResultType        = Null
    private type gettest_pathByIdActionType              = gettest_pathByIdActionRequestType => Try[gettest_pathByIdActionResultType]

    private val errorToStatusgettest_pathById: PartialFunction[Throwable, Status] = PartialFunction.empty[Throwable, Status]

    def gettest_pathByIdAction = (f: gettest_pathByIdActionType) => (id: String) => Action {        
            val result =                
                    new `Test-pathIdGetValidator`(id).errors match {
                        case e if e.isEmpty => processValidgettest_pathByIdRequest(f)((id))
                        case l =>
                            implicit val marshaller: Writeable[Seq[ParsingError]] = parsingErrors2Writable(gettest_pathByIdResponseMimeType)
                            BadRequest(l)
                    }
                
            result
    }

    private def processValidgettest_pathByIdRequest(f: gettest_pathByIdActionType)(request: gettest_pathByIdActionRequestType) = {
        implicit val gettest_pathByIdWritableJson = anyToWritable[gettest_pathByIdActionResultType](gettest_pathByIdResponseMimeType)
        val callerResult = f(request)
        val status = callerResult match {
            case Failure(error) => (errorToStatusgettest_pathById orElse defaultErrorMapping)(error)
            case Success(result) => gettest_pathByIdActionSuccessStatus(result)
        }
        status
    }
}