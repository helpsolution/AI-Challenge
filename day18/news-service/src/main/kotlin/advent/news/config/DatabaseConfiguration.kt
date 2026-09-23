package advent.news.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

@Configuration
class DatabaseConfiguration {
    /**
     * DataSource объявлен руками по двум причинам:
     *  - SQLite не создаёт каталог для файла БД, поэтому путь готовим сами;
     *  - у SQLite один писатель на файл, и пул из нескольких соединений даёт только SQLITE_BUSY.
     *    Планировщик и HTTP-запросы делят это соединение, поэтому держать его долго нельзя:
     *    ленты скачиваются до того, как берётся соединение, а не внутри транзакции.
     */
    @Bean
    fun dataSource(@Value("\${app.database-path}") databasePath: String): DataSource {
        Path.of(databasePath).toAbsolutePath().parent?.let(Files::createDirectories)
        return DataSourceBuilder.create()
            .type(HikariDataSource::class.java)
            .url("jdbc:sqlite:$databasePath")
            .build()
            .apply { maximumPoolSize = 1 }
    }
}
