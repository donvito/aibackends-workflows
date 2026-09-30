import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def prop = { String key ->
    exchange.context.resolvePropertyPlaceholders('{{' + key + '}}')
}

String key = prop('typesafe.api.key')
if (!key.trim()) {
    throw new IllegalStateException('TYPESAFE_API_KEY is required')
}

def email = exchange.getProperty('email', Map)
def questions = new JsonSlurper().parse(new File(prop('typesafe.questions.file')), 'UTF-8')
exchange.setProperty('jevQuestions', questions)
exchange.message.setHeader('Authorization', 'Bearer ' + key)
exchange.message.setHeader('Content-Type', 'application/json')

String payload = JsonOutput.toJson([
    model: prop('typesafe.model'),
    state: JsonOutput.toJson([
        from: email.from,
        to: email.to,
        subject: email.subject,
        body: email.body
    ]),
    questions: questions
])

exchange.setProperty('providerRequest', payload)
return payload
