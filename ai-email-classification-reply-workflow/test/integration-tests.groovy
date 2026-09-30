import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.apache.camel.support.DefaultExchange
import javax.sql.DataSource

def context = exchange.context
def producer = context.createProducerTemplate()
def db = context.registry.lookupByNameAndType('emailsDb', DataSource)
int checks = 0

def check = { boolean condition, String name ->
    if (!condition) {
        throw new AssertionError(name)
    }
    checks++
    println 'PASS ' + name
}

def submit = { Object input, Map headers = [:] ->
    def result = producer.request('direct:process-email') { e ->
        e.message.body = input
        headers.each { k, v ->
            e.message.setHeader(k, v)
        }
    }
    if (result.exception) {
        throw result.exception
    }
    return result.message.body
}

def row = { String id ->
    db.connection.withCloseable { conn ->
        conn.prepareStatement('SELECT * FROM email_results WHERE id = ?').withCloseable { statement ->
            statement.setString(1, id)
            statement.executeQuery().withCloseable { rs ->
                if (!rs.next()) {
                    return null
                }
                return (1..rs.metaData.columnCount).collectEntries { i ->
                    [(rs.metaData.getColumnName(i)): rs.getString(i)]
                }
            }
        }
    }
}

try {
    def inbox = new File(context.resolvePropertyPlaceholders('{{email.inbox.dir}}'))
    inbox.mkdirs()
    new File(inbox, 'file-json.json').setText(
        JsonOutput.toJson([
            subject: 'file-success',
            body: 'Please help me with this integration'
        ]),
        'UTF-8'
    )
    new File(inbox, 'file-eml.eml').bytes = new File('samples/refund-email.eml').bytes
    new File(inbox, 'invalid.json').setText('{"body":" "}', 'UTF-8')
    context.routeController.startRoute('email-files')
    long deadline = System.nanoTime() + 20_000_000_000L
    def paths = ['.done/file-json.json', '.done/file-eml.eml', '.failed/invalid.json']
    while (!paths.every { new File(inbox, it).exists() } && System.nanoTime() < deadline) {
        Thread.sleep(100)
    }
    check(
        new File(inbox, '.done/file-json.json').exists(),
        'file consumer persists and archives JSON input'
    )
    check(
        new File(inbox, '.done/file-eml.eml').exists(),
        'file consumer persists and archives EML input'
    )
    check(
        new File(inbox, '.failed/invalid.json').exists(),
        'invalid file moves to the failure folder'
    )
    context.routeController.stopRoute('email-files')

    def sample = [
        messageId: '<test@example.com>',
        from: "O'Brien <alex@example.com>",
        to: 'support@example.com',
        subject: 'success',
        body: "I'm frustrated. Please fix this.'); DROP TABLE email_results; --"
    ]
    def result = submit(sample, [
        Authorization: 'inbound-secret',
        CamelHttpUri: 'http://127.0.0.1:1/wrong',
        CamelHttpPath: '/wrong',
        CamelHttpMethod: 'DELETE'
    ])
    check(result.status == 'succeeded', 'both model calls succeed in parallel')
    check(
        result.jev.classification == 'technical_support' &&
        result.jev.intent == 'request_help' &&
        result.jev.sentiment == 'negative',
        'Jev category, intent and sentiment are retained'
    )
    check(result.openai.draft == 'Hello, we can help.', 'complete reply is retained')
    def stored = row(result.id)
    check(
        stored.email_body == sample.body && stored.sender == sample.from,
        'SQL parameters preserve quotes and injection-like text'
    )
    check(
        new JsonSlurper().parseText(stored.jev_result).response.answers.sentiment.confidence == 0.8,
        'full Jev answer and confidence are saved'
    )
    check(
        new JsonSlurper().parseText(stored.openai_result).response.usage.total_tokens == 25,
        'OpenAI response and usage are saved'
    )
    def jevRequest = new JsonSlurper().parseText(stored.jev_request)
    def openaiRequest = new JsonSlurper().parseText(stored.openai_request)
    check(
        jevRequest.model == 'jev-latest' &&
        new JsonSlurper().parseText(jevRequest.state).body == sample.body &&
        jevRequest.questions.keySet() == (['classification', 'intent', 'sentiment'] as Set),
        'Jev request retains model, email state and questions'
    )
    check(
        openaiRequest.model == 'provider/draft-model' &&
        openaiRequest.messages[0].content == new File('config/reply-prompt.txt').getText('UTF-8') &&
        new JsonSlurper().parseText(openaiRequest.messages[1].content).body == sample.body,
        'OpenAI request retains requested model, system prompt and email'
    )
    check(
        !stored.jev_request.contains('jev-test-key') && !stored.openai_request.contains('openai-test-key'),
        'request columns exclude API keys'
    )

    [
        'jev-error': 'partial',
        'openai-error': 'partial',
        'both-error': 'failed',
        'invalid-jev': 'partial',
        'truncated-draft': 'partial',
        'empty-draft': 'partial',
        'provider-error-body': 'partial'
    ].each { subject, status ->
        def r = submit(sample + [subject: subject])
        check(r.status == status && row(r.id).status == status, subject + ' persists both branch outcomes')
        check(
            row(r.id).jev_request && row(r.id).openai_request,
            subject + ' preserves both outgoing requests'
        )
        if (subject == 'jev-error') {
            check(r.openai.draft && r.jev.httpStatus == 500, 'Jev failure preserves successful reply')
            check(!row(r.id).jev_result.contains('upstream-secret'), 'upstream error body is not stored')
        }
        if (subject == 'openai-error') {
            check(
                r.jev.classification && r.openai.httpStatus == 500,
                'OpenAI failure preserves successful analysis'
            )
        }
        if (subject == 'provider-error-body') {
            check(
                r.openai.errorType == 'ProviderError' && r.openai.providerStatus == 503,
                'HTTP 200 provider error retains upstream status'
            )
            check(
                !row(r.id).openai_result.contains('upstream-secret'),
                'HTTP 200 provider error does not save raw messages'
            )
        }
    }

    def mime = new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(
            (
                'From: alex@example.com\r\nTo: support@example.com\r\n' +
                'Subject: mime\r\nMessage-ID: <mime@example.com>\r\nMIME-Version: 1.0\r\n' +
                'Content-Type: multipart/mixed; boundary="outer"\r\n\r\n' +
                '--outer\r\nContent-Type: multipart/alternative; boundary="inner"\r\n\r\n' +
                '--inner\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nPlain email\r\n' +
                '--inner\r\nContent-Type: text/html\r\n\r\n<p>HTML duplicate</p>\r\n--inner--\r\n' +
                '--outer\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename="secret.txt"\r\n\r\n' +
                'Attachment secret\r\n--outer--\r\n'
            ).getBytes('UTF-8')
        )
    )
    def mr = submit(mime)
    check(
        mr.email.body.trim() == 'Plain email',
        'IMAP MIME normalization prefers plain text and skips attachments'
    )
    check(mr.email.messageId == '<mime@example.com>', 'MIME Message-ID is retained')

    def eml = new File('samples/refund-email.eml').bytes
    def er = submit(eml, [CamelFileName: 'refund-email.eml'])
    check(er.email.subject == 'Charged twice this month' && er.status == 'succeeded', 'EML file input works')
    def json = new File('samples/support-email.json').getText('UTF-8')
    def jr = submit(json, [CamelFileName: 'support-email.json'])
    check(jr.email.subject == 'Integration keeps failing' && jr.status == 'succeeded', 'JSON file input works')

    def html = new MimeMessage(Session.getInstance(new Properties()))
    html.setSubject('html')
    html.setContent('<p>Hello &amp; thanks</p><script>hidden()</script>', 'text/html; charset=UTF-8')
    html.saveChanges()
    check(submit(html).email.body == 'Hello & thanks', 'HTML-only email is converted to readable text')

    [
        'empty': sample + [body: '  '],
        'too-long': sample + [body: 'x' * 50001],
        'invalid-type': sample + [body: 123]
    ].each { name, input ->
        def e = producer.request('direct:process-email') { it.message.body = input }
        check(e.exception != null, name + ' is rejected before provider calls')
    }

    // SQLite failure must reach the source consumer rather than acknowledge an unsaved email.
    db.connection.withCloseable { conn ->
        conn.createStatement().withCloseable {
            it.execute("CREATE TRIGGER reject_insert BEFORE INSERT ON email_results BEGIN SELECT RAISE(ABORT, 'test storage failure'); END")
        }
    }
    def failed = producer.request('direct:process-email') { it.message.body = sample + [subject: 'db-error'] }
    check(failed.exception != null, 'database insert failure propagates to the input')
    db.connection.withCloseable { conn ->
        conn.createStatement().withCloseable {
            it.execute('DROP TRIGGER reject_insert')
        }
    }

    println "${checks} integration checks passed"
    Runtime.runtime.halt(0)
} catch (Throwable failure) {
    failure.printStackTrace()
    Runtime.runtime.halt(1)
}
