package advent.cookingstate.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

@Configuration
class DatabaseConfiguration {
    @Bean
    fun dataSource(properties: CookingProperties): DataSource {
        val file = Path.of(properties.dbPath).absolute().normalize()
        file.parent?.createDirectories()
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
            poolName = "cooking-sqlite"
        })
    }
}
