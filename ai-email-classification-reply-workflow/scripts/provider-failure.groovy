import org.apache.camel.Exchange
import org.apache.camel.http.base.HttpOperationFailedException

def error = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception)

// Do not persist upstream error bodies or exception messages, which may echo email text or keys.
def result = [
    provider: exchange.getProperty('provider'),
    status: 'failed',
    errorType: error?.class?.simpleName ?: 'UnknownError',
    requestJson: exchange.getProperty('providerRequest', String)
]
if (error instanceof HttpOperationFailedException) {
    result.httpStatus = error.statusCode
}

return result
