import groovy.json.JsonSlurper

def response = new JsonSlurper().parseText(exchange.message.getBody(String))
if (!(response instanceof Map) || !(response.answers instanceof Map)) {
    throw new IllegalArgumentException('Jev response has no answers')
}

def questions = exchange.getProperty('jevQuestions', Map)
['classification', 'intent', 'sentiment'].each { name ->
    def answer = response.answers[name]
    if (!(answer instanceof Map) ||
        answer.type != 'choice' ||
        !questions[name].criteria.containsKey(answer.choice)) {
        throw new IllegalArgumentException('Jev response has an invalid ' + name)
    }
}

return [
    provider: 'jev',
    status: 'succeeded',
    model: response.model,
    classification: response.answers.classification.choice,
    intent: response.answers.intent.choice,
    sentiment: response.answers.sentiment.choice,
    response: response,
    requestJson: exchange.getProperty('providerRequest', String)
]
