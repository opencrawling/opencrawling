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
OpenCrawling — Complete Reference Documentation PDF Generator

Compiles the entire wiki contents from the opencrawling.wiki repository into a
single, publication-grade, beautifully styled PDF reference document with:
- Executive cover page
- Automated multi-part Table of Contents with dynamic page numbers
- Internal intra-wiki hyperlinking across all chapters
- GitHub-style alert callouts (Note, Tip, Important, Warning, Caution)
- Blueprint-style Architecture & Workflow diagram formatting
- Running headers, footers and page counters
- Robust error handling and offline fallbacks
"""

import sys
import os
import re
import argparse
import glob
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
        content: "OpenCrawling — Reference Documentation";
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        font-size: 8pt;
        color: #64748b;
        border-bottom: 1px solid #e2e8f0;
        padding-bottom: 4px;
    }
    @top-right {
        content: "Release v{VERSION}";
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        font-size: 8pt;
        color: #64748b;
        border-bottom: 1px solid #e2e8f0;
        padding-bottom: 4px;
    }
    @bottom-left {
        content: "https://github.com/OpenCrawling/opencrawling";
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        font-size: 8pt;
        color: #94a3b8;
        border-top: 1px solid #e2e8f0;
        padding-top: 4px;
    }
    @bottom-right {
        content: "Page " counter(page) " of " counter(pages);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        font-size: 8pt;
        color: #64748b;
        border-top: 1px solid #e2e8f0;
        padding-top: 4px;
    }
}

@page:first {
    margin: 0;
    @top-left { content: normal; border: none; }
    @top-right { content: normal; border: none; }
    @bottom-left { content: normal; border: none; }
    @bottom-right { content: normal; border: none; }
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 9.5pt;
    line-height: 1.55;
    color: #1e293b;
    margin: 0;
    padding: 0;
}

/* ==========================================================================
   Cover Page
   ========================================================================== */
.cover-page {
    page-break-after: always;
    height: 100vh;
    padding: 45mm 22mm 25mm 22mm;
    box-sizing: border-box;
    background: linear-gradient(135deg, #090d16 0%, #0f172a 40%, #1e293b 100%);
    color: #ffffff;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
}

.cover-header {
    margin-bottom: 20px;
}

.cover-badge {
    display: inline-block;
    background: #2563eb;
    color: #ffffff;
    padding: 5px 14px;
    font-size: 9pt;
    font-weight: 700;
    letter-spacing: 1.2px;
    border-radius: 20px;
    text-transform: uppercase;
    margin-bottom: 25px;
}

.cover-title {
    font-size: 38pt;
    font-weight: 800;
    line-height: 1.08;
    margin: 0 0 12px 0;
    color: #f8fafc;
    letter-spacing: -0.8px;
}

.cover-subtitle {
    font-size: 19pt;
    font-weight: 400;
    color: #94a3b8;
    margin: 0 0 30px 0;
    line-height: 1.3;
}

.cover-divider {
    width: 75px;
    height: 4px;
    background: #38bdf8;
    margin-bottom: 35px;
    border-radius: 2px;
}

.cover-features {
    list-style: none;
    padding: 0;
    margin: 0 0 40px 0;
}

.cover-features li {
    font-size: 10.5pt;
    color: #cbd5e1;
    margin-bottom: 10px;
    padding-left: 22px;
    position: relative;
    line-height: 1.4;
}

.cover-features li::before {
    content: "✓";
    position: absolute;
    left: 0;
    color: #38bdf8;
    font-weight: 800;
}

.cover-meta {
    border-top: 1px solid rgba(255, 255, 255, 0.18);
    padding-top: 22px;
    font-size: 9pt;
    color: #94a3b8;
    line-height: 1.7;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 24px;
}

.cover-meta strong {
    color: #f1f5f9;
}

/* ==========================================================================
   Table of Contents
   ========================================================================== */
.toc-page {
    page-break-before: always;
    page-break-after: always;
    padding-top: 10mm;
}

.toc-title {
    font-size: 24pt;
    font-weight: 800;
    color: #0f172a;
    border-bottom: 2px solid #2563eb;
    padding-bottom: 8px;
    margin-top: 0;
    margin-bottom: 20pt;
}

.toc-part-title {
    font-size: 11.5pt;
    font-weight: 800;
    color: #1e40af;
    margin-top: 18pt;
    margin-bottom: 8pt;
    text-transform: uppercase;
    letter-spacing: 0.8px;
    border-bottom: 1px solid #cbd5e1;
    padding-bottom: 3px;
    page-break-after: avoid;
}

.toc-items {
    margin-bottom: 12pt;
}

.toc-item {
    margin-bottom: 5.5pt;
    font-size: 9pt;
    line-height: 1.35;
    page-break-inside: avoid;
}

.toc-item a {
    text-decoration: none;
    color: #1e293b;
    display: block;
}

.toc-ch-num {
    font-weight: 700;
    color: #2563eb;
    margin-right: 6px;
    display: inline-block;
    min-width: 65px;
}

.toc-ch-title {
    color: #334155;
}

.toc-item a::after {
    content: leader('.') " " target-counter(attr(href), page);
    float: right;
    font-weight: 600;
    color: #64748b;
}

/* ==========================================================================
   Chapters & Document Structure
   ========================================================================== */
.wiki-chapter {
    page-break-before: always;
    margin-top: 0;
    padding-top: 6mm;
}

.chapter-header {
    margin-bottom: 16pt;
    border-bottom: 2px solid #e2e8f0;
    padding-bottom: 8pt;
}

.chapter-meta {
    font-size: 8pt;
    font-weight: 700;
    text-transform: uppercase;
    color: #2563eb;
    letter-spacing: 1px;
    margin-bottom: 4px;
}

.chapter-title {
    font-size: 18pt;
    font-weight: 800;
    color: #0f172a;
    margin: 0;
    line-height: 1.25;
}

/* Headings inside content */
h1 {
    font-size: 16pt;
    font-weight: 700;
    color: #0f172a;
    margin-top: 22pt;
    margin-bottom: 10pt;
    padding-bottom: 4pt;
    border-bottom: 1.5px solid #e2e8f0;
    page-break-after: avoid;
}

h2 {
    font-size: 13pt;
    font-weight: 700;
    color: #1d4ed8;
    margin-top: 18pt;
    margin-bottom: 8pt;
    page-break-after: avoid;
}

h3 {
    font-size: 11pt;
    font-weight: 600;
    color: #1e293b;
    margin-top: 14pt;
    margin-bottom: 6pt;
    page-break-after: avoid;
}

h4 {
    font-size: 9.5pt;
    font-weight: 600;
    color: #475569;
    margin-top: 10pt;
    margin-bottom: 4pt;
    page-break-after: avoid;
}

p {
    margin: 7pt 0;
}

/* Links */
a {
    color: #2563eb;
    text-decoration: none;
}

a:hover {
    text-decoration: underline;
}

/* Code & Syntax */
pre {
    background-color: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 9px 12px;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace;
    font-size: 7.5pt;
    line-height: 1.45;
    overflow-x: auto;
    page-break-inside: avoid;
    margin: 10pt 0;
    color: #1e293b;
}

code {
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace;
    font-size: 8pt;
    background-color: #f1f5f9;
    color: #b91c1c;
    padding: 1.5px 4.5px;
    border-radius: 3px;
}

pre code {
    background-color: transparent;
    color: inherit;
    padding: 0;
    font-size: 7.5pt;
}

/* Architecture & Workflow Diagram Containers */
.diagram-container {
    border: 1px solid #cbd5e1;
    background: #0f172a;
    border-radius: 6px;
    margin: 12pt 0;
    overflow: hidden;
    page-break-inside: avoid;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}

.diagram-badge {
    background: #1e293b;
    color: #38bdf8;
    padding: 5px 12px;
    font-size: 7.5pt;
    font-weight: 700;
    letter-spacing: 0.6px;
    text-transform: uppercase;
    border-bottom: 1px solid #334155;
}

.diagram-container pre {
    background: transparent;
    border: none;
    margin: 0;
    padding: 9px 12px;
    color: #f1f5f9;
    font-size: 7pt;
    line-height: 1.35;
}

.diagram-container pre code {
    color: #f1f5f9;
    background: transparent;
}

/* Tables */
table {
    width: 100%;
    border-collapse: collapse;
    margin: 12pt 0;
    font-size: 8pt;
    page-break-inside: avoid;
}

th, td {
    padding: 6px 8px;
    border: 1px solid #cbd5e0;
    text-align: left;
    vertical-align: top;
    word-break: break-word;
}

th {
    background-color: #f1f5f9;
    font-weight: 700;
    color: #0f172a;
}

tr:nth-child(even) td {
    background-color: #f8fafc;
}

/* Callout Alerts (GitHub style) */
.admonition {
    border-left: 4px solid #3b82f6;
    background-color: #eff6ff;
    padding: 8px 12px;
    margin: 10pt 0;
    border-radius: 0 6px 6px 0;
    page-break-inside: avoid;
    font-size: 9pt;
}

.admonition.tip {
    border-left-color: #10b981;
    background-color: #f0fdf4;
}

.admonition.important {
    border-left-color: #8b5cf6;
    background-color: #f5f3ff;
}

.admonition.warning {
    border-left-color: #f59e0b;
    background-color: #fffbeb;
}

.admonition.caution {
    border-left-color: #ef4444;
    background-color: #fef2f2;
}

.admonition-title {
    font-weight: 700;
    margin-bottom: 3px;
    text-transform: uppercase;
    font-size: 8pt;
    letter-spacing: 0.5px;
}

.admonition.note .admonition-title { color: #1d4ed8; }
.admonition.tip .admonition-title { color: #047857; }
.admonition.important .admonition-title { color: #6d28d9; }
.admonition.warning .admonition-title { color: #b45309; }
.admonition.caution .admonition-title { color: #b91c1c; }

/* Blockquotes */
blockquote {
    border-left: 3px solid #cbd5e1;
    margin: 10pt 0;
    padding-left: 10pt;
    color: #475569;
    font-style: italic;
}

/* Lists */
ul, ol {
    margin: 6pt 0;
    padding-left: 18pt;
}

li {
    margin-bottom: 3pt;
}

/* Images & Screenshots */
img {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 12pt auto;
    border-radius: 4px;
}

p img {
    display: inline-block;
    margin: 2px 2px;
    vertical-align: middle;
}

hr {
    border: none;
    border-top: 1px solid #e2e8f0;
    margin: 16pt 0;
}
"""


def safe_url_fetcher(url, timeout=5, ssl_context=None):
    """
    Safely fetches resources for WeasyPrint with a timeout and fallback
    to prevent external badge/image failures from stopping PDF compilation.
    """
    try:
        return weasyprint.default_url_fetcher(url, timeout=timeout, ssl_context=ssl_context)
    except Exception as e:
        # Return a blank 1x1 transparent SVG dummy on network/HTTP errors
        return {
            'string': b'<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>',
            'mime_type': 'image/svg+xml'
        }


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
        'IMPORTANT': ('important', '📌 Important'),
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
    Converts Mermaid code blocks into styled architecture blueprint containers.
    """
    def replacer(match):
        code_content = match.group(1).strip()
        escaped_code = code_content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return (
            f'\n<div class="diagram-container">\n'
            f'<div class="diagram-badge">Architecture & Workflow Blueprint</div>\n'
            f'<pre class="diagram-code"><code>{escaped_code}</code></pre>\n'
            f'</div>\n'
        )

    return re.sub(r'```mermaid\n(.*?)```', replacer, markdown_text, flags=re.DOTALL)


def rewrite_wiki_links(markdown_text: str, valid_slugs: set) -> str:
    """
    Rewrites internal wiki Markdown links [Text](Slug#anchor) to intra-document
    anchors [Text](#doc-Slug) so that clicking them navigates within the PDF.
    """
    def link_replacer(match):
        text = match.group(1)
        target = match.group(2)
        frag = match.group(3) or ""

        # Ignore external links or empty
        if re.match(r'^(https?://|mailto:|ftp:|#)', target, re.IGNORECASE):
            full_target = target + (f"#{frag}" if frag else "")
            return f"[{text}]({full_target})"

        clean_slug = target.replace('.md', '')
        if clean_slug in valid_slugs:
            return f"[{text}](#doc-{clean_slug})"

        full_target = target + (f"#{frag}" if frag else "")
        return f"[{text}]({full_target})"

    pattern = r'\[([^\]]+)\]\(([^)#\s]+)(?:#([^)]*))?\)'
    return re.sub(pattern, link_replacer, markdown_text)


def extract_title_and_clean_h1(markdown_text: str, default_title: str) -> tuple[str, str]:
    """
    Extracts the document title from the first `# H1` heading and removes it
    from the text so it can be formatted in the chapter header uniformly.
    """
    lines = markdown_text.splitlines()
    title = default_title
    clean_lines = []
    found_first_h1 = False

    for line in lines:
        if not found_first_h1 and line.startswith("# "):
            raw_title = line[2:].strip()
            # Clean trailing emojis or badge icons if needed, but preserve informative title
            title = raw_title
            found_first_h1 = True
            continue
        clean_lines.append(line)

    return title, "\n".join(clean_lines)


def parse_sidebar_and_discover_chapters(wiki_dir: str):
    """
    Parses _Sidebar.md to extract ordered documents categorized into Parts.
    Also collects any .md files not listed in _Sidebar.md as supplementary chapters.
    """
    sidebar_file = os.path.join(wiki_dir, "_Sidebar.md")
    all_md_files = {
        os.path.splitext(os.path.basename(f))[0]: f
        for f in glob.glob(os.path.join(wiki_dir, "*.md"))
        if not os.path.basename(f).startswith("_") and not os.path.basename(f).startswith(".")
    }

    # Predefined curated Parts mapping to provide clear, high-level organization
    part_structure = [
        {
            "part_num": "I",
            "part_title": "Platform Architecture & Getting Started",
            "slugs": [
                "Home",
                "User-and-Installation-Guide",
                "Quick-Start",
                "VM-and-Bare-Metal-Distribution-Installation",
                "Local-Development-and-Docker-Builds",
                "User-Guide",
                "Architecture",
                "Zero-Trust-Security-and-ACL-Management",
                "Document-Lifecycle-Actions-and-Tombstones",
                "Apache-Tika-4-Text-Extraction",
                "Configuration-Guide",
            ]
        },
        {
            "part_num": "II",
            "part_title": "Enterprise Connectors Reference",
            "slugs": [
                "Connectors",
                "Repository-Connector-Execution-Modes",
                "Alfresco-Content-Services-Repository-Connector",
                "Alfresco-Process-Services-Repository-Connector",
                "Flowable-Repository-Connector",
                "Camunda-Repository-Connector",
                "OASIS-CMIS-Repository-Connector",
                "Doxis-Repository-Connector",
                "M-Files-Repository-Connector",
                "SharePoint-Online-Repository-Connector",
                "Hyland-OpenArch-Integration",
                "JDBC-Repository-Connector",
                "Apache-StormCrawler-Repository-Connector",
                "gRPC-Push-Repository-Connector",
                "Apache-Solr-Output-Connector",
                "Luxir-Output-Connector",
                "Apache-SeaTunnel-Output-Connector",
                "Apache-Ozone-Output-Connector",
            ]
        },
        {
            "part_num": "III",
            "part_title": "Protocols, Storage & Developer Tooling",
            "slugs": [
                "Model-Context-Protocol",
                "Apache-Ozone-Claim-Check-Store",
                "Lakehouse-Audit-Reporting-and-Ledger",
                "AI-Powered-Observability-AIOps",
                "Auto-Narrativization Copilot",
                "Maven-Connector-Archetypes",
                "Java-Client-SDK",
                "OpenCrawling-CLI",
                "OpenAPI-Specification-and-Swagger-UI",
                "gRPC-Internal-Transport",
                "Migration-Guide",
            ]
        },
        {
            "part_num": "IV",
            "part_title": "Testing, Verification & Quality Assurance",
            "slugs": [
                "Integration-Test-Decoupled-Pipeline",
            ]
        },
        {
            "part_num": "V",
            "part_title": "Community & Project Governance",
            "slugs": [
                "Apache-Incubator-Proposal",
            ]
        }
    ]

    included_slugs = set()
    ordered_parts = []
    chapter_index = 1

    for part in part_structure:
        part_chapters = []
        for slug in part["slugs"]:
            # Normalize slug if needed (e.g. handle spaces or dashes)
            matched_key = None
            if slug in all_md_files:
                matched_key = slug
            else:
                dash_slug = slug.replace(" ", "-")
                if dash_slug in all_md_files:
                    matched_key = dash_slug

            if matched_key:
                file_path = all_md_files[matched_key]
                part_chapters.append({
                    "chapter_num": chapter_index,
                    "slug": matched_key,
                    "file_path": file_path,
                    "part_num": part["part_num"],
                    "part_title": part["part_title"]
                })
                included_slugs.add(matched_key)
                chapter_index += 1

        if part_chapters:
            ordered_parts.append({
                "part_num": part["part_num"],
                "part_title": part["part_title"],
                "chapters": part_chapters
            })

    # Supplementary chapters: any markdown file in the wiki not explicitly categorized
    remaining_slugs = sorted(set(all_md_files.keys()) - included_slugs)
    if remaining_slugs:
        supp_chapters = []
        for slug in remaining_slugs:
            file_path = all_md_files[slug]
            supp_chapters.append({
                "chapter_num": chapter_index,
                "slug": slug,
                "file_path": file_path,
                "part_num": "VI",
                "part_title": "Supplementary Documentation"
            })
            chapter_index += 1

        ordered_parts.append({
            "part_num": "VI",
            "part_title": "Supplementary Documentation",
            "chapters": supp_chapters
        })

    return ordered_parts, all_md_files


def generate_cover_html(version: str, date_str: str) -> str:
    return f"""
    <div class="cover-page">
        <div class="cover-header">
            <div class="cover-badge">Enterprise Data Federation & Zero-Trust RAG</div>
            <h1 class="cover-title">OpenCrawling</h1>
            <p class="cover-subtitle">Complete Reference Documentation</p>
            <div class="cover-divider"></div>
            <ul class="cover-features">
                <li>Pre-Built Docker Hub & Bare-Metal Multi-Service Decoupled Deployment</li>
                <li>20+ Enterprise Repository, Output & Migration Connectors</li>
                <li>Zero-Trust Security & Repository ACL Synchronization with Explicit Deny Precedence</li>
                <li>Secure Model Context Protocol (MCP) Server for AI Clients & LLM Agents</li>
                <li>Process-Isolated Apache Tika 4.x Pipes Fork Text Extraction & Anti-Crash Defenses</li>
                <li>Lakehouse Columnar Parquet Audit Ledger & Embedded Serverless DuckDB Analytics</li>
                <li>Contract-First OpenAPI 3.1 Contract Specification & Interactive Swagger UI</li>
                <li>High-Throughput Binary gRPC Protobuf Internal Streaming Transport</li>
            </ul>
        </div>
        <div class="cover-meta">
            <div><strong>Document:</strong> OpenCrawling Reference Documentation</div>
            <div><strong>Target Release:</strong> v{version}</div>
            <div><strong>Publication Date:</strong> {date_str}</div>
            <div><strong>License:</strong> Apache Software License 2.0</div>
            <div><strong>Official Repository:</strong> https://github.com/OpenCrawling/opencrawling</div>
            <div><strong>Documentation Source:</strong> OpenCrawling Official Wiki</div>
        </div>
    </div>
    """


def generate_toc_html(ordered_parts: list) -> str:
    html = ['<div class="toc-page">', '<h1 class="toc-title">Table of Contents</h1>']

    for part in ordered_parts:
        html.append(f'<div class="toc-part-title">Part {part["part_num"]}: {part["part_title"]}</div>')
        html.append('<div class="toc-items">')
        for ch in part["chapters"]:
            html.append(
                f'<div class="toc-item">'
                f'<a href="#doc-{ch["slug"]}">'
                f'<span class="toc-ch-num">Chapter {ch["chapter_num"]}</span>'
                f'<span class="toc-ch-title">{ch["title"]}</span>'
                f'</a>'
                f'</div>'
            )
        html.append('</div>')

    html.append('</div>')
    return "\n".join(html)


def generate_reference_pdf(wiki_dir: str, output_pdf_path: str, version: str):
    print("================================================================================")
    print(f"  OpenCrawling Reference Documentation Generator")
    print(f"  Version:   v{version}")
    print(f"  Wiki Dir:  {wiki_dir}")
    print(f"  Output:    {output_pdf_path}")
    print("================================================================================")

    if not os.path.exists(wiki_dir):
        print(f"[ERROR] Wiki directory not found: {wiki_dir}", file=sys.stderr)
        sys.exit(1)

    ordered_parts, all_md_files = parse_sidebar_and_discover_chapters(wiki_dir)
    valid_slugs = set(all_md_files.keys())

    md_converter = markdown.Markdown(extensions=[
        'extra',
        'tables',
        'fenced_code',
        'toc',
        'sane_lists'
    ])

    chapters_html_list = []
    total_chapters = sum(len(p["chapters"]) for p in ordered_parts)
    print(f"[INFO] Discovered {total_chapters} wiki chapters across {len(ordered_parts)} parts.")

    for part in ordered_parts:
        for ch in part["chapters"]:
            with open(ch["file_path"], "r", encoding="utf-8") as f:
                raw_content = f.read()

            default_title = ch["slug"].replace("-", " ")
            title, clean_content = extract_title_and_clean_h1(raw_content, default_title)
            ch["title"] = title

            # Process markdown elements
            content = process_mermaid_blocks(clean_content)
            content = process_github_alerts(content)
            content = rewrite_wiki_links(content, valid_slugs)

            # Convert to HTML
            md_converter.reset()
            body_html = md_converter.convert(content)

            chapter_html = (
                f'<article id="doc-{ch["slug"]}" class="wiki-chapter">\n'
                f'  <div class="chapter-header">\n'
                f'    <div class="chapter-meta">Part {ch["part_num"]}: {ch["part_title"]} &bull; Chapter {ch["chapter_num"]}</div>\n'
                f'    <h1 class="chapter-title">{title}</h1>\n'
                f'  </div>\n'
                f'  <div class="chapter-body">\n'
                f'    {body_html}\n'
                f'  </div>\n'
                f'</article>\n'
            )
            chapters_html_list.append(chapter_html)

    # Date and components
    date_str = datetime.now().strftime("%B %d, %Y")
    cover_html = generate_cover_html(version, date_str)
    toc_html = generate_toc_html(ordered_parts)
    css_content = CSS_STYLES.replace("{VERSION}", version)

    full_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <title>OpenCrawling Complete Reference Documentation (v{version})</title>
    <style>
    {css_content}
    </style>
</head>
<body>
    {cover_html}
    {toc_html}
    <main class="content-container">
        {"".join(chapters_html_list)}
    </main>
</body>
</html>
"""

    os.makedirs(os.path.dirname(os.path.abspath(output_pdf_path)), exist_ok=True)

    print(f"[INFO] Compiling PDF with WeasyPrint (resolving local assets from: {wiki_dir})...")
    html_doc = weasyprint.HTML(
        string=full_html,
        base_url=os.path.abspath(wiki_dir)
    )

    html_doc.write_pdf(output_pdf_path)

    pdf_size_mb = os.path.getsize(output_pdf_path) / (1024 * 1024)
    print(f"[SUCCESS] Reference documentation PDF created successfully:")
    print(f"          -> {output_pdf_path} ({pdf_size_mb:.2f} MB)")


def main():
    parser = argparse.ArgumentParser(description="Generate OpenCrawling Complete Wiki Reference Documentation PDF")
    parser.add_argument("--wiki-dir", default="",
                        help="Path to opencrawling.wiki directory")
    parser.add_argument("--output", default="",
                        help="Path to output PDF file (default: dist/OpenCrawling-Reference-Documentation-<version>.pdf)")
    parser.add_argument("--version", default="1.0.4",
                        help="Release version string (e.g. 1.0.4)")

    args = parser.parse_args()

    output_path = args.output
    if not output_path:
        output_path = f"dist/OpenCrawling-Reference-Documentation-{args.version}.pdf"

    # Auto-resolve wiki directory
    wiki_dir = args.wiki_dir
    if not wiki_dir or not os.path.exists(wiki_dir):
        candidates = [
            "opencrawling.wiki",
            "../opencrawling.wiki",
            os.path.join(os.path.dirname(__file__), "../../opencrawling.wiki"),
            os.path.join(os.path.dirname(__file__), "../opencrawling.wiki"),
            "wiki",
            os.path.join(os.getcwd(), "opencrawling.wiki")
        ]
        for candidate in candidates:
            if os.path.exists(candidate) and os.path.exists(os.path.join(candidate, "_Sidebar.md")):
                wiki_dir = os.path.abspath(candidate)
                break

    if not wiki_dir or not os.path.exists(wiki_dir):
        print("[ERROR] Could not auto-detect opencrawling.wiki directory. Pass --wiki-dir explicitly.", file=sys.stderr)
        sys.exit(1)

    generate_reference_pdf(wiki_dir, output_path, args.version)


if __name__ == "__main__":
    main()
