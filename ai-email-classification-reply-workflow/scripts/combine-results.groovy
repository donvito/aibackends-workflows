import java.time.Instant

if (!(body instanceof List) || body.size() != 2) {
    throw new IllegalStateException('Expected both provider results')
}

def results = body.collectEntries { [(it.provider): it] }
if (!results.jev || !results.openai) {
    throw new IllegalStateException('Missing provider result')
}

int succeeded = results.values().count { it.status == 'succeeded' }
def jevRequest = results.jev.remove('requestJson')
def openaiRequest = results.openai.remove('requestJson')

return [
    id: exchange.getProperty('email', Map).id,
    email: exchange.getProperty('email', Map),
    status: succeeded == 2 ? 'succeeded' : succeeded == 1 ? 'partial' : 'failed',
    jev: results.jev,
    openai: results.openai,
    jevRequest: jevRequest,
    openaiRequest: openaiRequest,
    processedAt: Instant.now().toString()
]
