package osp.leobert.android.maat

import android.app.Application
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import osp.leobert.android.maat.JobChunk.Companion.append
import osp.leobert.android.maat.JobChunk.Companion.info
import osp.leobert.android.maat.dag.DAG
import osp.leobert.android.maat.dag.Edge
import osp.leobert.android.maat.dag.Type
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

/**
 * <p><b>Package:</b> osp.leobert.android.maat </p>
 * <p><b>Classname:</b> Maat </p>
 * Created by leobert on 2020/9/23.
 */
class Maat(
    val application: Application, private val printChunkMax: Int,
    internal val logger: Logger, internal val callback: Callback? = null,
    internal val dispatcher: Dispatcher = JobDispatcher()
) {

    abstract class Logger {
        abstract val enable: Boolean
        abstract fun log(msg: String, throws: Throwable? = null)
    }

    class Callback(val onSuccess: (Maat) -> Unit, val onFailure: (Maat, JOB, Throwable) -> Unit)

    companion object {
        private var sInstance: Maat? = null

        @Synchronized
        @JvmOverloads
        fun init(
            application: Application, printChunkMax: Int,
            logger: Logger, callback: Callback? = null,
            dispatcher: Dispatcher = JobDispatcher()

        ): Maat {

            synchronized(Maat::class) {
                val tmp = sInstance
                if (tmp == null) {
                    synchronized(Maat::class) {
                        val s = Maat(application, printChunkMax, logger, callback, dispatcher)
                        sInstance = s
                        return s
                    }
                } else {
                    return tmp
                }
            }
        }

        @Synchronized
        fun getDefault(): Maat {
            return sInstance ?: throw MaatException("must call init at first")
        }

        @Synchronized
        @Throws(MaatException::class)
        fun release() {
            if (getDefault().hasFinished()) {
                sInstance = null
                return
            }

            throw MaatException("maat has not finished it's job")
        }


        private fun DAG<JOB>.bfs(): JobChunk {

            val zeroDeque = ArrayDeque<JOB>()
            val inDegrees = HashMap<JOB, Int>().apply {
                putAll(this@bfs.inDegreeCache)
            }
            inDegrees.forEach { (v, d) ->
                if (d == 0)
                    zeroDeque.offer(v)
            }

            val head = JobChunk.head()
            var currentChunk = head

            val tmpDeque = ArrayDeque<JOB>()

            while (zeroDeque.isNotEmpty() || tmpDeque.isNotEmpty()) {
                if (zeroDeque.isEmpty()) {
                    currentChunk = currentChunk.append()
                    zeroDeque.addAll(tmpDeque)
                    tmpDeque.clear()
                }
                zeroDeque.poll()?.let { vertex ->
                    currentChunk.addJob(vertex)

                    this.getEdgeContainsPoint(vertex, Type.X).forEach { edge ->
                        inDegrees[edge.to] = (inDegrees[edge.to] ?: 0).minus(edge.weight).apply {
                            if (this == 0)
                                tmpDeque.offer(edge.to)
                        }
                    }
                }
            }
            return head
        }
    }

    private val dag: DAG<JOB> = DAG({ job -> job.uniqueKey }, printChunkMax)
    private val start = arrayListOf("start")
    private val startJob = object : JOB() {
        override val uniqueKey: String = "start"
        override val dependsOn: List<String> = emptyList()
        override val dispatcher: CoroutineDispatcher = Dispatchers.Main

        override fun init(maat: Maat) {
        }

        override fun toString(): String {
            return uniqueKey
        }

    }

    private val allJobsCache = hashMapOf<String, JOB>()
    private val allJobsNameCache = linkedSetOf<String>()
    private val allDependsOnCache = hashSetOf<String>()
    private var currentJobChunk: JobChunk? = null

    fun append(job: JOB): Maat {
        allJobsCache[job.uniqueKey] = job
        allJobsNameCache.add(job.uniqueKey)
        allDependsOnCache.addAll(job.dependsOn)
        return this
    }

    @Throws(MaatException::class)
    fun start() {
        allJobsNameCache.forEach { allDependsOnCache.remove(it) }
        allDependsOnCache.takeIf { it.isNotEmpty() }
            ?.let { throw MaatException("missing jobs:$it") }

        //below 24
        for (entry in allJobsCache) {
            entry.value.let { job ->
                (job.dependsOn.takeIf { it.isNotEmpty() } ?: start).forEach {
                    dag.addEdge(Edge(allJobsCache[it] ?: startJob, job))
                }
            }
        }

        if (logger.enable) {
            logger.log(dag.debugMatrix())
        }

        dag.recursive(startJob, arrayListOf())
        if (dag.loopbackList.isNotEmpty()) {
            throw MaatException("cycle exist:${dag.loopbackList}")
        }

        if (logger.enable) {
            logger.log("全部路径：")
            dag.deepPathList.forEach {
                logger.log(it.toString())
            }
        }

        currentJobChunk = createJobChunk()

        if (logger.enable)
            logger.log("init order: ${currentJobChunk?.info()}")

        dispatcher.start(this, currentJobChunk)
    }

    @MainThread
    fun onJobFailed(job: JOB, throws: Throwable) {
        if (logger.enable)
            logger.log(
                "onJobFailed:${job.uniqueKey}, called on MainThread:${MaatUtil.isMainThread()}",
                throws
            )

        callback?.onFailure?.invoke(this, job, throws)
    }

    @MainThread
    internal fun onJobSuccess(job: JOB) {
        dispatcher.dispatchOnJobSuccess(this, currentJobChunk, job)
    }

    private fun createJobChunk(): JobChunk {
        return dag.bfs()
    }

    fun hasFinished(): Boolean {
        return currentJobChunk?.haveNext() != true
    }
}