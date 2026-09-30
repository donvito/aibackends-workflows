import java.util.concurrent.locks.ReentrantLock

exchange.context.registry.lookupByNameAndType('databaseInitLock', ReentrantLock).unlock()

return exchange.getProperty('email', Map)
