#!/usr/bin/env python3
#
# Copyright © 2026 the original author or authors (piergiorgio@apache.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
OpenCrawling — User & Installation Guide PDF Generator

Converts the markdown documentation from the wiki / docs into a
publication-grade, beautifully styled PDF document with cover page,
running headers/footers, syntax highlighting, and callout alerts.
"""

import sys
import os
import re
import argparse
from datetime import datetime

try:
    import markdown
except ImportError:
    print("[ERROR] 'markdown' library not found. Install via: pip install markdown", file=sys.stderr)
    sys.exit(1)

try:
    import weasyprint
except ImportError:
    print("[ERROR] 'weasyprint' library not found. Install via: pip install weasyprint", file=sys.stderr)
    sys.exit(1)


CSS_STYLES = """
@page {
    size: A4;
    margin: 22mm 18mm 22mm 18mm;
    @top-left {
        content: "OpenCrawling — User & Installation Guide";
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
        font-size: 8pt;
        color: #718096;
        border-bottom: 1px solid #e2e8f0;
        padding-bottom: 4px;
    }
    @top-right {
        content: "v{VERSION}";
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
        font-size: 8pt;
        color: #718096;
        border-bottom: 1px solid #e2e8f0;
        padding-bottom: 4px;
    }
    @bottom-left {
        content: "https://github.com/OpenCrawling/opencrawling";
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
        font-size: 8pt;
        color: #a0aec0;
        border-top: 1px solid #e2e8f0;
        padding-top: 4px;
    }
    @bottom-right {
        content: "Page " counter(page) " of " counter(pages);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
        font-size: 8pt;
        color: #718096;
        border-top: 1px solid #e2e8f0;
        padding-top: 4px;
    }
}

@page:first {
    margin-top: 0;
    margin-bottom: 0;
    margin-left: 0;
    margin-right: 0;
    @top-left { content: normal; border: none; }
    @top-right { content: normal; border: none; }
    @bottom-left { content: normal; border: none; }
    @bottom-right { content: normal; border: none; }
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 10pt;
    line-height: 1.6;
    color: #2d3748;
}

/* Cover Page */
.cover-page {
    page-break-after: always;
    height: 100vh;
    padding: 60mm 25mm 30mm 25mm;
    box-sizing: border-box;
    background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
    color: #ffffff;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
}

.cover-badge {
    display: inline-block;
    background: #3b82f6;
    color: #ffffff;
    padding: 6px 14px;
    font-size: 10pt;
    font-weight: 700;
    letter-spacing: 1px;
    border-radius: 20px;
    text-transform: uppercase;
    margin-bottom: 25px;
}

.cover-title {
    font-size: 34pt;
    font-weight: 800;
    line-height: 1.15;
    margin: 0 0 15px 0;
    color: #f8fafc;
    letter-spacing: -0.5px;
}

.cover-subtitle {
    font-size: 18pt;
    font-weight: 400;
    color: #94a3b8;
    margin: 0 0 40px 0;
    line-height: 1.4;
}

.cover-divider {
    width: 80px;
    height: 4px;
    background: #38bdf8;
    margin-bottom: 40px;
    border-radius: 2px;
}

.cover-features {
    list-style: none;
    padding: 0;
    margin: 0 0 60px 0;
}

.cover-features li {
    font-size: 11pt;
    color: #cbd5e1;
    margin-bottom: 12px;
    padding-left: 24px;
    position: relative;
}

.cover-features li::before {
    content: "✓";
    position: absolute;
    left: 0;
    color: #38bdf8;
    font-weight: 800;
}

.cover-meta {
    border-top: 1px solid rgba(255, 255, 255, 0.15);
    padding-top: 25px;
    font-size: 9.5pt;
    color: #94a3b8;
    line-height: 1.8;
}

.cover-meta strong {
    color: #f1f5f9;
}

/* Headings */
h1 {
    font-size: 20pt;
    font-weight: 700;
    color: #1a202c;
    margin-top: 28pt;
    margin-bottom: 12pt;
    padding-bottom: 6pt;
    border-bottom: 2px solid #e2e8f0;
    page-break-after: avoid;
}

h2 {
    font-size: 15pt;
    font-weight: 700;
    color: #2b6cb0;
    margin-top: 22pt;
    margin-bottom: 10pt;
    page-break-after: avoid;
}

h3 {
    font-size: 12pt;
    font-weight: 600;
    color: #2d3748;
    margin-top: 16pt;
    margin-bottom: 8pt;
    page-break-after: avoid;
}

h4 {
    font-size: 10.5pt;
    font-weight: 600;
    color: #4a5568;
    margin-top: 12pt;
    margin-bottom: 6pt;
    page-break-after: avoid;
}

/* Links */
a {
    color: #2b6cb0;
    text-decoration: none;
}

a:hover {
    text-decoration: underline;
}

/* Code & Syntax */
pre {
    background-color: #f7fafc;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 10px 14px;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace;
    font-size: 8pt;
    line-height: 1.45;
    overflow-x: auto;
    page-break-inside: avoid;
    margin: 12pt 0;
    color: #2d3748;
}

code {
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace;
    font-size: 8.5pt;
    background-color: #edf2f7;
    color: #c53030;
    padding: 2px 5px;
    border-radius: 4px;
}

pre code {
    background-color: transparent;
    color: inherit;
    padding: 0;
    font-size: 8pt;
}

/* Tables */
table {
    width: 100%;
    border-collapse: collapse;
    margin: 14pt 0;
    font-size: 8.5pt;
    page-break-inside: avoid;
}

th, td {
    padding: 7px 10px;
    border: 1px solid #cbd5e0;
    text-align: left;
    vertical-align: top;
}

th {
    background-color: #edf2f7;
    font-weight: 700;
    color: #2d3748;
}

tr:nth-child(even) td {
    background-color: #f7fafc;
}

/* Callout Alerts (GitHub style) */
.admonition {
    border-left: 4px solid #3182ce;
    background-color: #ebf8ff;
    padding: 10px 14px;
    margin: 14pt 0;
    border-radius: 0 6px 6px 0;
    page-break-inside: avoid;
    font-size: 9.5pt;
}

.admonition.tip {
    border-left-color: #38a169;
    background-color: #f0fff4;
}

.admonition.warning {
    border-left-color: #dd6b20;
    background-color: #fffaf0;
}

.admonition.caution {
    border-left-color: #e53e3e;
    background-color: #fff5f5;
}

.admonition-title {
    font-weight: 700;
    margin-bottom: 4px;
    text-transform: uppercase;
    font-size: 8.5pt;
    letter-spacing: 0.5px;
}

.admonition.note .admonition-title { color: #2b6cb0; }
.admonition.tip .admonition-title { color: #2f855a; }
.admonition.warning .admonition-title { color: #c05621; }
.admonition.caution .admonition-title { color: #c53030; }

/* Blockquotes */
blockquote {
    border-left: 3px solid #cbd5e0;
    margin: 12pt 0;
    padding-left: 12pt;
    color: #4a5568;
    font-style: italic;
}

/* Lists */
ul, ol {
    margin: 8pt 0;
    padding-left: 20pt;
}

li {
    margin-bottom: 4pt;
}

/* Horizontal Rule */
hr {
    border: none;
    border-top: 1px solid #e2e8f0;
    margin: 20pt 0;
}
"""


def process_github_alerts(markdown_text: str) -> str:
    """
    Transforms GitHub-style alerts:
      > [!NOTE]
      > message
    into HTML styled callout blocks.
    """
    alert_types = {
        'NOTE': ('note', 'ℹ️ Note'),
        'TIP': ('tip', '💡 Tip'),
        'IMPORTANT': ('note', '📌 Important'),
        'WARNING': ('warning', '⚠️ Warning'),
        'CAUTION': ('caution', '🛑 Caution')
    }

    lines = markdown_text.splitlines()
    output_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        match = re.match(r'^>\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*$', line.strip(), re.IGNORECASE)
        if match:
            alert_kind = match.group(1).upper()
            css_class, title = alert_types.get(alert_kind, ('note', alert_kind))
            alert_content = []
            i += 1
            while i < len(lines) and (lines[i].startswith('>') or lines[i].strip() == ''):
                content_line = re.sub(r'^>\s?', '', lines[i])
                alert_content.append(content_line)
                i += 1
            
            block_html = (
                f'<div class="admonition {css_class}">\n'
                f'<div class="admonition-title">{title}</div>\n'
                f'{"<br/>".join(alert_content)}\n'
                f'</div>\n'
            )
            output_lines.append(block_html)
            continue
        else:
            output_lines.append(line)
            i += 1

    return "\n".join(output_lines)


def process_mermaid_blocks(markdown_text: str) -> str:
    """
    Pre-processes Mermaid code blocks to render clean text/code blocks in PDF.
    """
    return re.sub(
        r'```mermaid\n(.*?)```',
        r'```text\n[Architecture Diagram]\n\1```',
        markdown_text,
        flags=re.DOTALL
    )


def generate_cover_html(version: str, date_str: str) -> str:
    return f"""
    <div class="cover-page">
        <div>
            <div class="cover-badge">Enterprise RAG & Data Federation</div>
            <h1 class="cover-title">OpenCrawling</h1>
            <p class="cover-subtitle">User & Installation Guide</p>
            <div class="cover-divider"></div>
            <ul class="cover-features">
                <li>Pre-Built Docker Hub Multi-Service Decoupled Deployment</li>
                <li>Bare-Metal & Virtual Machine Distribution ZIP Installation</li>
                <li>Zero-Trust Security & Repository ACL Federation</li>
                <li>Secure Model Context Protocol (MCP) Server for AI Clients</li>
                <li>Process-Isolated Apache Tika 4.x Text Extraction</li>
                <li>Lakehouse Columnar Parquet Audit Reporting & Ledger</li>
            </ul>
        </div>
        <div class="cover-meta">
            <div><strong>Document:</strong> OpenCrawling User & Installation Manual</div>
            <div><strong>Release Version:</strong> v{version}</div>
            <div><strong>Publication Date:</strong> {date_str}</div>
            <div><strong>License:</strong> Apache Software License 2.0</div>
            <div><strong>Official Repository:</strong> https://github.com/OpenCrawling/opencrawling</div>
        </div>
    </div>
    """


def generate_pdf(input_md_path: str, output_pdf_path: str, version: str):
    print(f"[INFO] Reading markdown input from: {input_md_path}")
    if not os.path.exists(input_md_path):
        print(f"[ERROR] Input file not found: {input_md_path}", file=sys.stderr)
        sys.exit(1)

    with open(input_md_path, 'r', encoding='utf-8') as f:
        md_content = f.read()

    # Pre-process markdown
    md_content = process_mermaid_blocks(md_content)
    md_content = process_github_alerts(md_content)

    # Convert Markdown to HTML
    md = markdown.Markdown(extensions=[
        'extra',
        'tables',
        'fenced_code',
        'toc',
        'sane_lists'
    ])
    body_html = md.convert(md_content)

    # Prepare Cover & CSS
    date_str = datetime.now().strftime("%B %d, %Y")
    cover_html = generate_cover_html(version, date_str)
    css_content = CSS_STYLES.replace("{VERSION}", version)

    full_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <title>OpenCrawling User & Installation Guide (v{version})</title>
    <style>
    {css_content}
    </style>
</head>
<body>
    {cover_html}
    <div class="content-container">
        {body_html}
    </div>
</body>
</html>
"""

    # Ensure output directory exists
    os.makedirs(os.path.dirname(os.path.abspath(output_pdf_path)), exist_ok=True)

    print(f"[INFO] Generating PDF via WeasyPrint: {output_pdf_path}")
    html_doc = weasyprint.HTML(string=full_html, base_url=os.path.dirname(os.path.abspath(input_md_path)))
    html_doc.write_pdf(output_pdf_path)

    pdf_size_kb = os.path.getsize(output_pdf_path) / 1024
    print(f"[SUCCESS] PDF generated successfully: {output_pdf_path} ({pdf_size_kb:.1f} KB)")


def main():
    parser = argparse.ArgumentParser(description="Generate OpenCrawling User & Installation Guide PDF")
    parser.add_argument("--input", default="docs/User-and-Installation-Guide.md",
                        help="Path to input markdown documentation file")
    parser.add_argument("--output", default="dist/OpenCrawling-User-and-Installation-Guide.pdf",
                        help="Path to output PDF file")
    parser.add_argument("--version", default="1.0.4",
                        help="Release version string (e.g. 1.0.4)")

    args = parser.parse_args()

    # Auto-resolve input file if relative path from repo root
    input_file = args.input
    if not os.path.exists(input_file):
        candidates = [
            os.path.join("opencrawling.wiki", "User-and-Installation-Guide.md"),
            os.path.join("..", "opencrawling.wiki", "User-and-Installation-Guide.md"),
            os.path.join("docs", "User-and-Installation-Guide.md"),
            "User-and-Installation-Guide.md"
        ]
        for candidate in candidates:
            if os.path.exists(candidate):
                input_file = candidate
                break

    generate_pdf(input_file, args.output, args.version)


if __name__ == "__main__":
    main()
