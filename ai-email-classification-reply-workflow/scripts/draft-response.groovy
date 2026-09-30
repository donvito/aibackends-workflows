import groovy.json.JsonSlurper

def response = new JsonSlurper().parseText(exchange.message.getBody(String))

// OpenAI-compatible gateways can report an upstream failure inside an HTTP 200 response.
if (response instanceof Map && response.error) {
    def result = [
        provider: 'openai',
        status: 'failed',
        errorType: 'ProviderError',
        requestJson: exchange.getProperty('providerRequest', String)
    ]
    def code = response.error instanceof Map ? response.error.code : null
    if (code instanceof Number && code >= 100 && code <= 599) {
        result.providerStatus = code.intValue()
    }

    // Keep the structured code, without saving raw provider messages or metadata.
    return result
}

def choice = response instanceof Map && response.choices instanceof List ? response.choices.find() : null
def text = choice?.message?.content
if (!(text instanceof String) || !text.trim() || choice.finish_reason != 'stop') {
    throw new IllegalArgumentException('OpenAI did not return a complete reply draft')
}

return [
    provider: 'openai',
    status: 'succeeded',
    model: response.model,
    draft: text.trim(),
    response: response,
    requestJson: exchange.getProperty('providerRequest', String)
]
