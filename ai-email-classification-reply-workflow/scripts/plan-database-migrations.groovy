// The SQL component puts PRAGMA table_info results in this header.
def columns = exchange.message.getHeader('databaseColumns', List).collect { it.name } as Set

return ['jev_request', 'openai_request']
    .findAll { !columns.contains(it) }
    .collect { 'sql/add-' + it.replace('_', '-') + '.sql' }
