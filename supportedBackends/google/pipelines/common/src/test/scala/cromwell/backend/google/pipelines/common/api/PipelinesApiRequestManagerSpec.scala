package cromwell.backend.google.pipelines.common.api

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestActorRef, TestProbe, _}
import com.google.api.client.googleapis.batch.BatchRequest
import cromwell.backend.BackendSingletonActorAbortWorkflow
import cromwell.backend.google.pipelines.common.PipelinesApiTestConfig
import cromwell.backend.google.pipelines.common.api.PipelinesApiRequestManager.{JesApiRunCreationQueryFailed, PAPIRunCreationRequest, PAPIStatusPollRequest}
import cromwell.backend.google.pipelines.common.api.TestPipelinesApiRequestManagerSpec._
import cromwell.backend.standard.StandardAsyncJob
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.util.AkkaTestUtil
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import org.scalatest.concurrent.Eventually
import org.scalatest.{FlatSpecLike, Matchers}

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.Random

class PipelinesApiRequestManagerSpec extends TestKitSuite("PipelinesApiRequestManagerSpec") with FlatSpecLike with Matchers with Eventually {

  behavior of "PipelinesApiRequestManager"

  implicit val TestExecutionTimeout = 10.seconds.dilated
  implicit val DefaultPatienceConfig = PatienceConfig(TestExecutionTimeout)
  val AwaitAlmostNothing = 30.milliseconds.dilated
  val BatchSize = 5
  val registryProbe = TestProbe().ref
  val workflowId = WorkflowId.randomId()

  private def makePollRequest(snd: ActorRef, jobId: StandardAsyncJob) = new PAPIStatusPollRequest(workflowId, snd, null, jobId) {
    override def contentLength = 0
  }

  private def makeCreateRequest(contentSize: Long, snd: ActorRef, workflowId: WorkflowId = workflowId) = new PAPIRunCreationRequest(workflowId, snd, null) {
    override def contentLength = contentSize
  }

  it should "queue up and dispense status poll requests, in order" in {
    val statusPoller = TestProbe(name = "StatusPoller")
    val jaqmActor: TestActorRef[TestPipelinesApiRequestManager] = TestActorRef(TestPipelinesApiRequestManager.props(registryProbe, statusPoller.ref))

    var statusRequesters = ((0 until BatchSize * 2) map { i => i -> TestProbe(name = s"StatusRequester_$i") }).toMap

    // Initially, we should have no work:
    jaqmActor.tell(msg = PipelinesApiRequestManager.RequestJesPollingWork(BatchSize), sender = statusPoller.ref)
    statusPoller.expectMsg(max = TestExecutionTimeout, obj = PipelinesApiRequestManager.NoWorkToDo)

    // Send a few status poll requests:
    statusRequesters foreach { case (index, probe) =>
      jaqmActor.tell(msg = makePollRequest(probe.ref, StandardAsyncJob(index.toString)), sender = probe.ref)
    }

    // Should have no messages to the actual statusPoller yet:
    statusPoller.expectNoMsg(max = AwaitAlmostNothing)

    // Verify batches:
    2 times {
      jaqmActor.tell(msg = PipelinesApiRequestManager.RequestJesPollingWork(BatchSize), sender = statusPoller.ref)
      statusPoller.expectMsgPF(max = TestExecutionTimeout) {
        case PipelinesApiRequestManager.PipelinesApiWorkBatch(workBatch) =>
          val requesters = statusRequesters.take(BatchSize)
          statusRequesters = statusRequesters.drop(BatchSize)

          val zippedWithRequesters = workBatch.toList.zip(requesters)
          zippedWithRequesters foreach { case (pollQuery, (index, testProbe)) =>
            pollQuery.requester should be(testProbe.ref)
            pollQuery.asInstanceOf[PAPIStatusPollRequest].jobId should be(StandardAsyncJob(index.toString))
          }
        case other => fail(s"Unexpected message: $other")
      }
    }

    // Finally, we should have no work:
    jaqmActor.tell(msg = PipelinesApiRequestManager.RequestJesPollingWork(BatchSize), sender = statusPoller.ref)
    statusPoller.expectMsg(max = TestExecutionTimeout, obj = PipelinesApiRequestManager.NoWorkToDo)

    jaqmActor.underlyingActor.testPollerCreations should be(1)
  }

  it should "reject create requests above maxBatchSize" in {
    val statusPoller = TestProbe(name = "StatusPoller")
    val jaqmActor: TestActorRef[TestPipelinesApiRequestManager] = TestActorRef(TestPipelinesApiRequestManager.props(registryProbe, statusPoller.ref))

    val statusRequester = TestProbe()

    // Send a create request
    val request = makeCreateRequest(15 * 1024 * 1024, statusRequester.ref)
    jaqmActor.tell(msg = request, sender = statusRequester.ref)

    statusRequester.expectMsgClass(classOf[JesApiRunCreationQueryFailed])

    jaqmActor.underlyingActor.queueSize shouldBe 0
  }

  it should "respect the maxBatchSize when beheading the queue" in {
    val statusPoller = TestProbe(name = "StatusPoller")
    // maxBatchSize is 14MB, which mean we can take 2 queries of 5MB but not 3
    val jaqmActor: TestActorRef[TestPipelinesApiRequestManager] = TestActorRef(TestPipelinesApiRequestManager.props(registryProbe, statusPoller.ref))

    val statusRequester = TestProbe()

    // Enqueue 3 create requests
    1 to 3 foreach { _ =>
      val request = makeCreateRequest(5 * 1024 * 1024, statusRequester.ref)
      jaqmActor.tell(msg = request, sender = statusRequester.ref)
    }

    // ask for a batch
    jaqmActor.tell(msg = PipelinesApiRequestManager.RequestJesPollingWork(BatchSize), sender = statusPoller.ref)

    // We should get only 2 requests back
    statusPoller.expectMsgPF(max = TestExecutionTimeout) {
      case PipelinesApiRequestManager.PipelinesApiWorkBatch(workBatch) => workBatch.toList.size shouldBe 2
      case other => fail(s"Unexpected message: $other")
    }

    // There should be 1 left in the queue
    jaqmActor.underlyingActor.queueSize shouldBe 1
  }

  AkkaTestUtil.actorDeathMethods(system) foreach { case (name, stopMethod) =>
    /*
      This test creates two statusPoller ActorRefs which are handed to the TestJesApiQueryManager. Work is added to that query
      manager and then the first statusPoller requests work and is subsequently killed. The expectation is that:

      - The work will return to the workQueue of the query manager
      - The query manager will have registered a new statusPoller
      - That statusPoller is the second ActorRef (and artifact of TestJesApiQueryManager)
     */
    it should s"catch polling actors if they $name, recreate them and add work back to the queue" in {
      val statusPoller1 = TestActorRef(Props(new AkkaTestUtil.DeathTestActor()), TestActorRef(new AkkaTestUtil.StoppingSupervisor()))
      val statusPoller2 = TestActorRef(Props(new AkkaTestUtil.DeathTestActor()), TestActorRef(new AkkaTestUtil.StoppingSupervisor()))
      val jaqmActor: TestActorRef[TestPipelinesApiRequestManager] = TestActorRef(TestPipelinesApiRequestManager.props(registryProbe, statusPoller1, statusPoller2), s"TestJesApiQueryManager-${Random.nextInt()}")

      val emptyActor = system.actorOf(Props.empty)

      // Send a few status poll requests:
      BatchSize indexedTimes { index =>
        val request = makePollRequest(emptyActor, StandardAsyncJob(index.toString))
        jaqmActor.tell(msg = request, sender = emptyActor)
      }

      jaqmActor.tell(msg = PipelinesApiRequestManager.RequestJesPollingWork(BatchSize), sender = statusPoller1)

      stopMethod(statusPoller1)

      eventually {
        jaqmActor.underlyingActor.testPollerCreations should be (2)
        jaqmActor.underlyingActor.queueSize should be (BatchSize)
        jaqmActor.underlyingActor.statusPollerEquals(statusPoller2) should be (true)
      }
    }
  }

  it should "remove run requests from queue when receiving an abort message" in {
    val statusPoller = TestProbe(name = "StatusPoller")
    // maxBatchSize is 14MB, which mean we can take 2 queries of 5MB but not 3
    val jaqmActor: TestActorRef[TestPipelinesApiRequestManager] = TestActorRef(TestPipelinesApiRequestManager.props(registryProbe, statusPoller.ref))

    // Enqueue 3 create requests
    val workflowIdA = WorkflowId.randomId()
    val workflowIdB = WorkflowId.randomId()
    jaqmActor ! makeCreateRequest(0, emptyActor, workflowIdA)
    jaqmActor ! makeCreateRequest(0, emptyActor, workflowIdA)
    jaqmActor ! makeCreateRequest(0, emptyActor, workflowIdB)

    // abort workflow A
    jaqmActor ! BackendSingletonActorAbortWorkflow(workflowIdA)

    // It should remove all and only run requests for workflow A 
    eventually {
      jaqmActor.underlyingActor.queueSize shouldBe 1
      jaqmActor.underlyingActor.workQueue.head.asInstanceOf[PipelinesApiRequestManager.PAPIRunCreationRequest].workflowId shouldBe workflowIdB
    }
  }
}

object TestPipelinesApiRequestManagerSpec {
  implicit class intWithTimes(n: Int) {
    def times(f: => Unit) = 1 to n foreach { _ => f }
    def indexedTimes(f: Int => Unit) = 0 until n foreach { i => f(i) }
  }
}

/**
  * This test class allows us to hook into the JesApiQueryManager's makeStatusPoller and provide our own TestProbes instead
  */
class TestPipelinesApiRequestManager(qps: Int Refined Positive, requestWorkers: Int Refined Positive, registry: ActorRef, statusPollerProbes: ActorRef*)
  extends PipelinesApiRequestManager(qps, requestWorkers, registry)(new MockPipelinesRequestHandler) {
  var testProbes: Queue[ActorRef] = _
  var testPollerCreations: Int = _

  private def init() = {
    testProbes = Queue(statusPollerProbes: _*)
    testPollerCreations = 0
  }

  override private[api] lazy val nbWorkers = 1
  override private[api] def resetAllWorkers() = {
    val pollers = Vector.fill(1) { makeWorkerActor() }
    pollers.foreach(context.watch)
    pollers
  }

  override private[api] def makeWorkerActor(): ActorRef = {
    // Initialize the queue, if necessary:
    if (testProbes == null) {
      init()
    }

    // Register that the creation was requested:
    testPollerCreations += 1

    // Pop the queue to get the next test probe:
    val (probe, newQueue) = testProbes.dequeue
    testProbes = newQueue
    probe
  }

  def queueSize = workQueue.size
  def statusPollerEquals(otherStatusPoller: ActorRef) = statusPollers sameElements Array(otherStatusPoller)
}

class MockPipelinesRequestHandler extends PipelinesApiBatchHandler {
  override def makeBatchRequest = ???
  override def enqueue[T <: PipelinesApiRequestManager.PAPIApiRequest](papiApiRequest: T, batchRequest: BatchRequest, pollingManager: ActorRef) = ???
}

object TestPipelinesApiRequestManager {
  import PipelinesApiTestConfig._

  def props(registryProbe: ActorRef, statusPollers: ActorRef*): Props = Props(new TestPipelinesApiRequestManager(jesConfiguration.qps, jesConfiguration.papiRequestWorkers, registryProbe, statusPollers: _*))
}