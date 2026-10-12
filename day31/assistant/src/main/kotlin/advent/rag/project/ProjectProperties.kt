package advent.rag.project
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("project")
data class ProjectProperties(val root: String, val mcpUrl: String)
