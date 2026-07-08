# Contributing to OpenCrawling

First off, thank you for taking the time to contribute! 🎉

The following is a set of guidelines for contributing to OpenCrawling. These are mostly guidelines, not rules. Use your best judgment, and feel free to propose changes to this document in a pull request.

---

## Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [How Can I Contribute?](#how-can-i-contribute)
   - [Reporting Bugs](#reporting-bugs)
   - [Suggesting Enhancements](#suggesting-enhancements)
   - [Pull Requests](#pull-requests)
3. [Styleguides](#styleguides)
   - [Java Styleguide](#java-styleguide)
   - [Frontend Styleguide](#frontend-styleguide)
   - [Commit Messages](#commit-messages)

---

## Code of Conduct

This project and everyone participating in it is governed by the [OpenCrawling Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

---

## How Can I Contribute?

### Reporting Bugs

If you find a bug in the source code, you can help us by submitting an issue to our GitHub repository. Before submitting, please check the existing issues to make sure the bug hasn't already been reported.

When filing a bug report, please include:
- A clear summary in the title.
- Steps to reproduce the issue.
- Your environment details (OS, JDK version, Docker version, etc.).
- Expected vs. actual behavior.
- Relevant logs, stack traces, or screenshots.

### Suggesting Enhancements

If you have ideas to make the project better, feel free to open a feature request.
Please describe:
- The problem you want to solve.
- The proposed solution or feature behavior.
- Alternatives you've considered.

### Pull Requests

To contribute code:
1. **Fork** the repository and create your branch from `main`.
2. **Setup environment** according to the [README.md](README.md).
3. If you've added code that should be tested, add tests.
4. Ensure the test suite passes locally.
5. Format your code according to our style guidelines.
6. Submit a **Pull Request** referencing the issue you are addressing.

---

## Styleguides

### Java Styleguide

- We follow standard **Google Java Style**.
- Keep methods short and focused.
- Leverage modern Java 25 preview features responsibly (e.g., Structured Concurrency).
- Avoid raw concurrency implementations where Virtual Threads or Structured Concurrency features are appropriate.
- Ensure preview features are enabled using the Maven compiler configuration.

### Frontend Styleguide

- Use TypeScript for all UI code.
- Format code using Prettier / ESLint configured in the repository.
- Avoid inline Tailwind styles where reusable component classes make sense.

### Commit Messages

We encourage following the **Conventional Commits** specification:
- `feat: ...` for a new feature.
- `fix: ...` for a bug fix.
- `docs: ...` for documentation changes.
- `style: ...` for formatting, missing semicolons, etc.
- `refactor: ...` for restructuring code.
- `test: ...` for adding missing tests.
- `chore: ...` for updating build tasks, package manager configs, etc.
