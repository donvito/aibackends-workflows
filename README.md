# AI Backends Workflows

The workflows here are free for companies to use and adapt under the [Apache 2.0 license](LICENSE).

This repository shares practical examples of connecting AI models, APIs, data, and business systems with Apache Camel. Use them to learn, experiment, and build a starting point for workflows tailored to your own processes.

Each example lives in its own directory with a README covering its purpose, requirements, configuration, and usage.

## Workflows

- [Email classification and reply drafting](ai-email-classification-reply-workflow/README.md)

## Running an example

1. Choose a workflow from the list above and open its README.
2. Install the runtime and tools specified by that example. The Camel examples use Java and [Camel JBang](https://camel.apache.org/manual/camel-jbang.html).
3. Change into the workflow directory and configure the required credentials and settings. If an `.env.example` file is provided, copy it to `.env` and fill in the values.
4. Start the workflow using the command in its README, then follow its instructions to submit sample inputs and inspect the results.
5. Follow the example's test instructions to verify your changes as you adapt it.

Keep credentials and local runtime data out of version control. Requirements, external services, and available commands are documented in each workflow's README.

## Contributing

Issues and pull requests are welcome for bug fixes, documentation improvements, and new examples. Keep new workflows in self-contained directories with clear setup instructions, sample configuration, and usage examples, and add them to the list above.

## Custom AI integrations

If you need custom AI integration work, reach out at [aibackends.com](https://aibackends.com/).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
