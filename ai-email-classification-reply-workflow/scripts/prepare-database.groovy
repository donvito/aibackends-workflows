import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

def file = Path.of(exchange.context.resolvePropertyPlaceholders('{{db.file}}')).toAbsolutePath()
Files.createDirectories(file.parent)

// Serialize schema checks and ALTERs when file and IMAP consumers initialize concurrently.
exchange.context.registry.lookupByNameAndType('databaseInitLock', ReentrantLock).lock()

return body
