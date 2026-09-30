import groovy.json.JsonOutput

def prop = { String key ->
    exchange.context.resolvePropertyPlaceholders('{{' + key + '}}')
}

String key = prop('openai.api.key')
if (!key.trim()) {
    throw new IllegalStateException('OPENAI_API_KEY is required')
}

String endpoint = prop('openai.url').trim()
if (!endpoint) {
    endpoint = prop('openai.base.url').trim().replaceAll('/+$', '') + '/chat/completions'
}

def uri = new URI(endpoint)
if (!(uri.scheme in ['http', 'https']) || !uri.host || uri.fragment) {
    throw new IllegalArgumentException('OpenAI endpoint must be an absolute HTTP(S) URL')
}

def email = exchange.getProperty('email', Map)
// The branch has already cleared input headers; this override is derived solely from configuration.
exchange.message.setHeader('CamelHttpUri', endpoint)
exchange.message.setHeader('Authorization', 'Bearer ' + key)
exchange.message.setHeader('Content-Type', 'application/json')

String payload = JsonOutput.toJson([
    model: prop('openai.model'),
    max_completion_tokens: prop('openai.max.completion.tokens').toInteger(),
    messages: [
        [
            role: 'system',
            content: new File(prop('openai.prompt.file')).getText('UTF-8')
        ],
        [
            role: 'user',
            content: JsonOutput.toJson([
                from: email.from,
                to: email.to,
                subject: email.subject,
                body: email.body
            ])
        ]
    ]
])

exchange.setProperty('providerRequest', payload)
return payload
