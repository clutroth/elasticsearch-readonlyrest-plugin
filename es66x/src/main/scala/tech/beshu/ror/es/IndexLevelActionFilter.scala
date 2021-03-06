/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es

import java.util.function.Supplier

import monix.execution.Scheduler.Implicits.global
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.env.Environment
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.SecurityPermissionException
import tech.beshu.ror.accesscontrol.domain.UriPath.CurrentUserMetadataPath
import tech.beshu.ror.accesscontrol.request.EsRequestContext
import tech.beshu.ror.boot.{Engine, Ror}
import tech.beshu.ror.es.providers.{EsAuditSink, EsIndexJsonContentProvider}
import tech.beshu.ror.es.request.regular.RegularRequestHandler
import tech.beshu.ror.es.request.usermetadata.CurrentUserMetadataRequestHandler
import tech.beshu.ror.es.request.{RequestInfo, RorNotReadyResponse}
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.es.utils.AccessControllerHelper._

import scala.concurrent.duration._
import scala.language.postfixOps

class IndexLevelActionFilter(clusterService: ClusterService,
                             client: NodeClient,
                             threadPool: ThreadPool,
                             env: Environment,
                             remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]])
  extends ActionFilter with Logging {

  private val rorInstance = {
    val startingResult = Ror
      .start(env.configFile, new EsAuditSink(client), new EsIndexJsonContentProvider(client))
      .runSyncUnsafe(1 minute)
    startingResult match {
      case Right(instance) =>
        RorInstanceSupplier.update(instance)
        instance
      case Left(ex) =>
        throw StartingFailureException.from(ex)
    }
  }

  override def order(): Int = 0

  def stop(): Unit = rorInstance.stop()

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    doPrivileged {
      (rorInstance.engine, ThreadRepo.getRestChannel) match {
        case (_, None) => chain.proceed(task, action, request, listener)
        case (_, _) if action.startsWith("internal:") => chain.proceed(task, action, request, listener)
        case (None, Some(channel)) => channel.sendResponse(RorNotReadyResponse.create(channel))
        case (Some(engine), Some(channel)) => handleRequest(engine, task, action, request, listener, chain, channel)
      }
    }
  }

  private def handleRequest[Request <: ActionRequest, Response <: ActionResponse](engine: Engine,
                                                                                  task: Task,
                                                                                  action: String,
                                                                                  request: Request,
                                                                                  listener: ActionListener[Response],
                                                                                  chain: ActionFilterChain[Request, Response],
                                                                                  channel: RestChannel): Unit = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        val requestInfo = new RequestInfo(channel, task.getId, action, request, clusterService, threadPool, remoteClusterService)
        val requestContext = requestContextFrom(requestInfo)
        requestContext.uriPath match {
          case CurrentUserMetadataPath(_) =>
            val handler = new CurrentUserMetadataRequestHandler(engine, task, action, request, listener, chain, channel, threadPool)
            handler.handle(requestInfo, requestContext)
          case _ =>
            val handler = new RegularRequestHandler(engine, task, action, request, listener, chain, channel, threadPool)
            handler.handle(requestInfo, requestContext)
        }
      case None =>
        listener.onFailure(new Exception("Cluster service not ready yet. Cannot continue"))
    }
  }

  private def requestContextFrom(requestInfo: RequestInfo) =
    EsRequestContext
      .from(requestInfo)
      .fold(
        ex => throw new SecurityPermissionException("Cannot create request context object", ex),
        identity
      )
}
