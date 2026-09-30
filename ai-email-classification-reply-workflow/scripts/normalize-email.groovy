import groovy.json.JsonSlurper
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.time.Instant

// Ignore attachments; prefer plain text in multipart/alternative, otherwise use HTML text.
Closure textOf
textOf = { Part part ->
    if (Part.ATTACHMENT.equalsIgnoreCase(part.disposition) || part.fileName) {
        return ''
    }
    if (part.isMimeType('text/plain')) {
        return part.content.toString()
    }
    if (part.isMimeType('text/html')) {
        return org.jsoup.Jsoup.parse(part.content.toString()).text()
    }
    if (part.isMimeType('multipart/*')) {
        Multipart parts = (Multipart) part.content
        def texts = []
        for (int i = 0; i < parts.count; i++) {
            Part child = parts.getBodyPart(i)
            String text = textOf(child)
            if (part.isMimeType('multipart/alternative') && child.isMimeType('text/plain') && text.trim()) {
                return text
            }
            if (text.trim()) {
                texts.add(text)
            }
        }
        // An alternative offers the same message in multiple formats.
        return part.isMimeType('multipart/alternative') ? (texts ? texts[0] : '') : texts.join('\n')
    }
    return ''
}

def input = body
if (!(input instanceof Map) && !(input instanceof Message)) {
    String name = exchange.message.getHeader('CamelFileName', String) ?: ''
    if (name.toLowerCase().endsWith('.eml')) {
        input = new MimeMessage(
            Session.getInstance(new Properties()),
            new ByteArrayInputStream(exchange.message.getBody(byte[].class))
        )
    } else {
        input = new JsonSlurper().parseText(exchange.message.getBody(String))
    }
}

Map email
if (input instanceof Message) {
    email = [
        messageId: input.getHeader('Message-ID')?.first(),
        from: input.from?.collect { it.toString() }?.join(', ') ?: '',
        to: input.getRecipients(Message.RecipientType.TO)?.collect { it.toString() }?.join(', ') ?: '',
        subject: input.subject ?: '',
        body: textOf(input),
        receivedAt: (input.receivedDate ?: input.sentDate)?.toInstant()?.toString()
    ]
} else if (input instanceof Map) {
    email = [:]
    ['messageId', 'from', 'to', 'subject', 'body', 'receivedAt'].each { key ->
        if (input[key] != null && !(input[key] instanceof String)) {
            throw new IllegalArgumentException("${key} must be a string")
        }
        email[key] = input[key]
    }
} else {
    throw new IllegalArgumentException('Email must be a JSON object or a MIME message')
}

if (!email.body?.trim()) {
    throw new IllegalArgumentException('Email body must not be empty')
}

int limit = exchange.context.resolvePropertyPlaceholders('{{email.max.body.chars}}').toInteger()
if (email.body.length() > limit) {
    throw new IllegalArgumentException('Email body exceeds email.max.body.chars')
}

email.from = email.from ?: ''
email.to = email.to ?: ''
email.subject = email.subject ?: ''
email.receivedAt = email.receivedAt ?: Instant.now().toString()

// Each processing attempt gets its own row; Message-ID remains available for correlation.
email.id = UUID.randomUUID().toString()
email.source = exchange.message.getHeader('CamelFileName', String) ?: 'imap-or-direct'

return email
