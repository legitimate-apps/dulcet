// Recorded deterministic helper replay, 2026-10-30.
// Old helper extracted unchanged from fff506de4c6ff95465479669a1dd371492ea20da.
// Fixed helper extracted unchanged from 6e1ba53a8567119142520ca4657f91181d4493b8.
// Lifecycle and UI below are fakes selecting one allowed interleaving, not Android evidence.
// Run with: kotlinc android-reconnect-helper-replay.kt -include-runtime -d replay.jar
//           java -jar replay.jar

class MainActivity
object Lifecycle { enum class State { CREATED, RESUMED } }
class RetiredTag(val tag:String): IllegalStateException("ensureDisplayed: retired tag $tag")
class MissingOutcome: IllegalStateException("caller: expected different-server-identity rejection absent")
class ActivityScenario<T>(private val world:World) {
    fun moveToState(state:Lifecycle.State) { world.moveToState(state) }
}
class World(private val effectEnabled:Boolean=true) {
    val trace=mutableListOf<String>()
    val states=mutableListOf<Lifecycle.State>()
    val existsTags=mutableListOf<String>()
    val clicks=mutableListOf<String>()
    val tags=mutableSetOf("library.reconnect")
    var reconnectStarts=0
    var reconnectCompletions=0
    var rejection:String?=null
    private var pending=false
    fun moveToState(state:Lifecycle.State) {
        states+=state;trace+="lifecycle:$state"
        if(state==Lifecycle.State.RESUMED && states.dropLast(1).lastOrNull()==Lifecycle.State.CREATED) {
            reconnectStarts++; trace+="product:auto-reconnect-start"
            pending=effectEnabled
        }
    }
    private fun completeReconnect() {
        if(!pending) return
        pending=false;reconnectCompletions++
        tags.remove("library.reconnect")
        tags.add("library.connection.retry")
        rejection="different-server-identity rejection"
        trace+="product:retire-library.reconnect; publish-identity-rejection+library.connection.retry"
    }
    fun exists(tag:String):Boolean {
        existsTags+=tag
        val snapshot=tag in tags
        trace+="exists:$tag=$snapshot"
        // Controlled completion between the helper's read and subsequent click.
        if(tag=="library.reconnect" && snapshot) completeReconnect()
        return snapshot
    }
    fun click(tag:String) {
        clicks+=tag;trace+="click:ensureDisplayed:$tag"
        if(tag !in tags) throw RetiredTag(tag)
        trace+="click:performed:$tag"
    }
    fun awaitCallerOutcome() {
        trace+="caller:await-identity-rejection"
        completeReconnect()
        if(rejection!="different-server-identity rejection" || "library.connection.retry" !in tags) throw MissingOutcome()
        check("library.reconnect" !in tags)
        trace+="caller:identity-rejection-observed"
    }
}
abstract class Probe(protected val world:World) {
    protected fun exists(tag:String)=world.exists(tag)
    protected fun click(tag:String)=world.click(tag)
    abstract fun run(activity:ActivityScenario<MainActivity>)
}
class OldProbe(world:World):Probe(world) {
    private fun reconnect(activity:ActivityScenario<MainActivity>) {
        activity.moveToState(Lifecycle.State.CREATED);activity.moveToState(Lifecycle.State.RESUMED)
        if(exists("library.reconnect"))click("library.reconnect")
        else if(exists("library.connection.retry"))click("library.connection.retry")
        else if(exists("wishlist.refresh"))click("wishlist.refresh")
    }
    override fun run(activity:ActivityScenario<MainActivity>) { reconnect(activity) }
}
class FixedProbe(world:World):Probe(world) {
    private fun reconnect(activity:ActivityScenario<MainActivity>) {
        // LibraryLifecycle.ON_START initiates reconnect. Callers wait for its server/UI
        // outcome; a manual fallback can retire between observing its tag and clicking it.
        activity.moveToState(Lifecycle.State.CREATED);activity.moveToState(Lifecycle.State.RESUMED)
    }
    override fun run(activity:ActivityScenario<MainActivity>) { reconnect(activity) }
}
class MissingResumedProbe(world:World):Probe(world) {
    private fun reconnect(activity:ActivityScenario<MainActivity>) {
        // LibraryLifecycle.ON_START initiates reconnect. Callers wait for its server/UI
        // outcome; a manual fallback can retire between observing its tag and clicking it.
        activity.moveToState(Lifecycle.State.CREATED);/* deliberate negative control: RESUMED omitted */
    }
    override fun run(activity:ActivityScenario<MainActivity>) { reconnect(activity) }
}

fun main() {
    val repetitions=1000
    var oldReproduced=0;var fixedPassed=0;var missingResumeCaught=0;var noEffectCaught=0
    repeat(repetitions) { i ->
        val old=World();val oldFailure=runCatching { OldProbe(old).run(ActivityScenario(old)) }.exceptionOrNull()
        check(oldFailure is RetiredTag && oldFailure.tag=="library.reconnect")
        check(old.states==listOf(Lifecycle.State.CREATED,Lifecycle.State.RESUMED))
        check(old.existsTags==listOf("library.reconnect"))
        check(old.clicks==listOf("library.reconnect"))
        check(old.reconnectStarts==1 && old.reconnectCompletions==1)
        // Even the old helper fails only after the modeled product has delivered the expected effect.
        old.awaitCallerOutcome();oldReproduced++
        val fixed=World();FixedProbe(fixed).run(ActivityScenario(fixed));fixed.awaitCallerOutcome()
        check(fixed.states==listOf(Lifecycle.State.CREATED,Lifecycle.State.RESUMED))
        check(fixed.reconnectStarts==1 && fixed.reconnectCompletions==1)
        check(fixed.existsTags.isEmpty() && fixed.clicks.isEmpty());fixedPassed++
        val missing=World();MissingResumedProbe(missing).run(ActivityScenario(missing))
        check(runCatching { missing.awaitCallerOutcome() }.exceptionOrNull() is MissingOutcome)
        check(missing.states==listOf(Lifecycle.State.CREATED) && missing.reconnectStarts==0);missingResumeCaught++
        val noEffect=World(effectEnabled=false);FixedProbe(noEffect).run(ActivityScenario(noEffect))
        check(runCatching { noEffect.awaitCallerOutcome() }.exceptionOrNull() is MissingOutcome)
        check(noEffect.states==listOf(Lifecycle.State.CREATED,Lifecycle.State.RESUMED))
        check(noEffect.reconnectStarts==1 && noEffect.reconnectCompletions==0);noEffectCaught++
        if(i==0) {
            println("OLD trace: "+old.trace.joinToString(" -> "))
            println("OLD failure: ${oldFailure.message}")
            println("FIXED trace: "+fixed.trace.joinToString(" -> "))
            println("MISSING_RESUMED control trace: "+missing.trace.joinToString(" -> "))
            println("NO_EFFECT control trace: "+noEffect.trace.joinToString(" -> "))
        }
    }
    println("RESULT old_race_reproduced=$oldReproduced fixed_passed=$fixedPassed missing_resumed_caught=$missingResumeCaught no_effect_caught=$noEffectCaught iterations_per_case=$repetitions total_cases=${repetitions*4}")
    println("SCOPE isolated JVM helper probe; fake lifecycle and UI; deterministic selected interleaving; not Android/Compose/emulator/device evidence")
}
