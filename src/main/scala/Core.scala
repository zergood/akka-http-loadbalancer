import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer

import scala.concurrent.ExecutionContext

trait Core {
  implicit val system: ActorSystem = ActorSystem("http-balancer")
  implicit val materializer: ActorFlowMaterializer = ActorFlowMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
}
