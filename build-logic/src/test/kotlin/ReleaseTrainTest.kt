import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseTrainTest {
    private val trains = ReleaseTrain.entries

    @Test
    fun trainsShareNothing() {
        val fields = listOf(ReleaseTrain::id, ReleaseTrain::group, ReleaseTrain::versionProperty, ReleaseTrain::stageTask)
        for (field in fields) {
            assertEquals(trains.size, trains.map(field).distinct().size, "two trains share a ${field.name}")
        }
        val projects = trains.flatMap { it.publishedProjects }
        assertEquals(projects.distinct(), projects, "a project rides two trains")
    }

    @Test
    fun aTagNamesExactlyOneTrain() {
        // release.yml picks the train by the tag's prefix, so no train's tag may also start with another's prefix.
        for (train in trains) {
            val tag = "${train.tagPrefix}1.2.3"
            assertEquals(listOf(train), trains.filter { tag.startsWith(it.tagPrefix) }, tag)
        }
    }

    @Test
    fun theNamesTheWorkflowsUse() {
        assertEquals(listOf("stageDelva", "stageCliKit"), trains.map { it.stageTask })
        assertEquals(listOf("v", "cli-kit-v"), trains.map { it.tagPrefix })
        assertEquals(listOf("delva.version", "clikit.version"), trains.map { it.versionProperty })
    }
}
