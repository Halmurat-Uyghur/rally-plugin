package com.intellij.plugins.rally

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskState
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.tasks.impl.BaseRepositoryType
import com.intellij.util.Consumer
import java.util.EnumSet
import javax.swing.Icon

/**
 * Rally repository type - factory for creating Rally repositories and editors
 * This is registered as an extension point in plugin.xml
 */
class RallyRepositoryType : BaseRepositoryType<RallyRepository>() {

    override fun getName(): String {
        return "Rally"
    }

    override fun getIcon(): Icon {
        // Load Rally icon from resources
        return IconLoader.getIcon("/icons/rally.svg", RallyRepositoryType::class.java)
    }

    override fun createRepository(): RallyRepository {
        return RallyRepository(this)
    }

    override fun getRepositoryClass(): Class<RallyRepository> {
        return RallyRepository::class.java
    }

    override fun createEditor(
        repository: RallyRepository,
        project: Project,
        consumer: Consumer<in RallyRepository>
    ): TaskRepositoryEditor {
        return RallyRepositoryEditor(project, repository, consumer)
    }

    /**
     * Get possible states for Rally tasks
     */
    override fun getPossibleTaskStates(): EnumSet<TaskState> {
        return EnumSet.of(
            TaskState.OPEN,
            TaskState.IN_PROGRESS,
            TaskState.RESOLVED
        )
    }
}
