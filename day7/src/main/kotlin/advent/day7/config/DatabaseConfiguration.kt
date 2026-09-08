package advent.day7.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

/**
 * DataSource собираем сами, а не через `spring.datasource`, по двум причинам.
 *
 * Первая: SQLite не создаёт папку под файл базы и падает с невнятной ошибкой, если её нет.
 * Здесь папка создаётся до открытия соединения, поэтому первый запуск на чистой машине
 * просто работает.
 *
 * Вторая: у SQLite один писатель на файл. Пул из одного соединения превращает
 * состязание за запись в честную очередь вместо `SQLITE_BUSY`. Это не тормозит запрос:
 * соединение занято только на время SELECT и INSERT, а самое долгое — ожидание модели —
 * проходит вообще без соединения.
 */
@Configuration
class DatabaseConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun dataSource(properties: ChatProperties): DataSource {
        val file: Path = Path.of(properties.dbPath).absolute().normalize()
        file.parent?.createDirectories()
        log.info("История диалога: {}", file)

        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:$file"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
                poolName = "sqlite"
            },
        )
    }
}
