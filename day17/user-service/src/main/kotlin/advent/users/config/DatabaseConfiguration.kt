package advent.users.config

import advent.users.storage.SqliteExceptionTranslator
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.support.SQLExceptionTranslator
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

@Configuration
class DatabaseConfiguration {
    /**
     * DataSource объявлен руками по двум причинам:
     *  - SQLite не создаёт каталог для файла БД, поэтому путь готовим сами;
     *  - у SQLite один писатель на файл, и пул из нескольких соединений даёт только SQLITE_BUSY.
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

    /** JdbcTemplate подхватывает этот бин и начинает различать ошибки SQLite. */
    @Bean
    fun sqliteExceptionTranslator(): SQLExceptionTranslator = SqliteExceptionTranslator()
}
