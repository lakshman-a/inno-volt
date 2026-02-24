# SONAR FIX SOLUTION — COMPLETE IMPLEMENTATION PLAN FOR COPILOT

## OVERVIEW
Update the existing Sonar Fix solution in the GenAI Platform to fully integrate with
SonarQube API. This is a TWO-PHASE solution:
- Phase A (Scan): Connect to SonarQube → fetch issues → present categorized plan to user
- Phase B (Fix): User selects issues → LLM generates fixes → compile validation → deliver fixed project

DO NOT break any existing solutions (unit_test_gen, api_doc_gen, func_test_gen).
Reuse existing shared services: llm_client, maven_runner, file_manager, prompt_manager.

---

## FILE CHANGES REQUIRED

### NEW FILES TO CREATE:
1. `backend/app/services/shared/sonar_client.py` — SonarQube API HTTP client
2. `backend/app/prompts/sonar_fix/system.txt` — REPLACE existing with improved version
3. `backend/app/prompts/sonar_fix/fix_issues.txt` — REPLACE existing with improved version
4. `backend/app/prompts/sonar_fix/scan_local.txt` — NEW prompt for local AI analysis mode

### FILES TO UPDATE:
5. `backend/app/services/solutions/sonar_fix/solution.py` — REWRITE with full pipeline
6. `backend/app/models/schemas.py` — Update SonarFixConfig and SonarFixResult
7. `backend/app/api/routes/main.py` — No change needed (already routes sonar_fix)
8. `frontend/src/app/features/sonar-fix/sonar-fix.component.ts` — REWRITE with two-phase UI
9. `frontend/src/app/features/adoption/adoption.component.ts` — Add sonar fix metrics

---

## FILE 1: backend/app/services/shared/sonar_client.py

```python
"""
SonarQube API Client.
Handles all communication with SonarQube server.
Authentication: Bearer token (User type token from SonarQube → My Account → Security → Generate Tokens).

FMR Internal SonarQube URL patterns:
  - QA: https://sonar-qa.fmr.com
  - Prod: https://sonar.fmr.com
  - Community: https://sonarqube.fmr.com
  
If you don't know the FMR domain, the user provides it in the UI.
For local testing, use: https://next.sonarqube.com/sonarqube (public SonarQube instance by SonarSource)
"""

import httpx
import base64
from typing import List, Dict, Optional, Any
from loguru import logger


class SonarQubeClient:
    """HTTP client for SonarQube REST API."""

    def __init__(self, sonar_url: str, token: str, branch: str = "main"):
        """
        Args:
            sonar_url: Base URL of SonarQube server (e.g., https://sonar.fmr.com)
            token: User-type bearer token from SonarQube
            branch: Git branch name (default: main)
        """
        self.base_url = sonar_url.rstrip("/")
        self.token = token
        self.branch = branch
        self.headers = {
            "Authorization": f"Bearer {token}"
        }
        # NOTE: Some older SonarQube versions use Basic auth with token as username:
        # "Authorization": f"Basic {base64.b64encode(f'{token}:'.encode()).decode()}"
        # If Bearer fails with 401, fall back to Basic auth automatically.

    async def validate_connection(self) -> Dict[str, Any]:
        """
        Step 1: Validate that the SonarQube server is reachable and token is valid.
        
        API: GET /api/system/status
        Headers: Authorization: Bearer {token}
        
        Expected Response (200):
        {
            "id": "E0518B6F-...",
            "version": "10.8.1.100263",
            "status": "UP"
        }
        
        Possible Errors:
        - Connection refused → "Cannot connect to SonarQube at {url}"
        - 401 → "Authentication failed. Check your token."
        - Timeout → "SonarQube server not responding"
        
        Returns: {"connected": True, "version": "10.8.1", "status": "UP"}
        """
        try:
            async with httpx.AsyncClient(timeout=30, verify=False) as client:
                resp = await client.get(
                    f"{self.base_url}/api/system/status",
                    headers=self.headers
                )
                if resp.status_code == 401:
                    # Try Basic auth fallback
                    basic_headers = {
                        "Authorization": f"Basic {base64.b64encode(f'{self.token}:'.encode()).decode()}"
                    }
                    resp = await client.get(f"{self.base_url}/api/system/status", headers=basic_headers)
                    if resp.status_code == 200:
                        self.headers = basic_headers  # Switch to Basic auth for all future calls
                    else:
                        return {"connected": False, "error": "Authentication failed. Check your SonarQube token."}
                
                if resp.status_code == 200:
                    data = resp.json()
                    return {"connected": True, "version": data.get("version", "unknown"), "status": data.get("status", "unknown")}
                else:
                    return {"connected": False, "error": f"SonarQube returned status {resp.status_code}"}
        except httpx.ConnectError:
            return {"connected": False, "error": f"Cannot connect to SonarQube at {self.base_url}"}
        except httpx.TimeoutException:
            return {"connected": False, "error": "SonarQube server not responding (timeout)"}
        except Exception as e:
            return {"connected": False, "error": str(e)}

    async def validate_project(self, project_key: str) -> Dict[str, Any]:
        """
        Step 2: Validate that the project exists and token has Browse permission.
        
        API: GET /api/components/show?component={project_key}
        
        Expected Response (200):
        {
            "component": {
                "key": "com.fidelity:portfolio-service",
                "name": "Portfolio Service",
                "qualifier": "TRK",
                "visibility": "private"
            }
        }
        
        Possible Errors:
        - 404 → "Project not found: {project_key}"
        - 403 → "Token lacks Browse permission on this project"
        
        Returns: {"found": True, "name": "Portfolio Service", "key": "..."}
        """
        try:
            async with httpx.AsyncClient(timeout=30, verify=False) as client:
                params = {"component": project_key}
                resp = await client.get(f"{self.base_url}/api/components/show", params=params, headers=self.headers)
                
                if resp.status_code == 200:
                    data = resp.json()
                    comp = data.get("component", {})
                    return {"found": True, "name": comp.get("name", ""), "key": comp.get("key", "")}
                elif resp.status_code == 404:
                    return {"found": False, "error": f"Project not found: {project_key}"}
                elif resp.status_code == 403:
                    return {"found": False, "error": f"Token lacks Browse permission on project: {project_key}"}
                else:
                    return {"found": False, "error": f"Unexpected response: {resp.status_code}"}
        except Exception as e:
            return {"found": False, "error": str(e)}

    async def get_project_summary(self, project_key: str) -> Dict[str, Any]:
        """
        Step 3: Get project dashboard metrics (the numbers shown on Sonar dashboard).
        
        API: GET /api/measures/component
            ?component={project_key}
            &branch={branch}
            &metricKeys=bugs,vulnerabilities,code_smells,coverage,ncloc,
                        sqale_index,reliability_rating,security_rating,
                        sqale_rating,cognitive_complexity,security_hotspots,
                        duplicated_lines_density
        
        Expected Response (200):
        {
            "component": {
                "key": "com.fidelity:portfolio-service",
                "name": "Portfolio Service",
                "measures": [
                    {"metric": "bugs", "value": "12"},
                    {"metric": "vulnerabilities", "value": "5"},
                    {"metric": "code_smells", "value": "87"},
                    {"metric": "coverage", "value": "23.4"},
                    {"metric": "ncloc", "value": "8500"},
                    {"metric": "sqale_index", "value": "1440"},
                    {"metric": "cognitive_complexity", "value": "342"},
                    {"metric": "security_hotspots", "value": "3"},
                    {"metric": "duplicated_lines_density", "value": "4.2"},
                    {"metric": "reliability_rating", "value": "3.0"},
                    {"metric": "security_rating", "value": "4.0"},
                    {"metric": "sqale_rating", "value": "2.0"}
                ]
            }
        }
        
        EXTRACT from response:
            Loop through component.measures array.
            Build a dict: { metric_name: value } for each measure.
            sqale_index is in MINUTES — convert to hours by dividing by 60.
            Ratings: 1.0=A, 2.0=B, 3.0=C, 4.0=D, 5.0=E
        
        Returns: {
            "bugs": 12, "vulnerabilities": 5, "code_smells": 87,
            "coverage": 23.4, "ncloc": 8500, "tech_debt_hours": 24.0,
            "cognitive_complexity": 342, "security_hotspots": 3,
            "reliability_rating": "C", "security_rating": "D", "maintainability_rating": "B"
        }
        """
        metrics = "bugs,vulnerabilities,code_smells,coverage,ncloc,sqale_index," \
                  "cognitive_complexity,security_hotspots,duplicated_lines_density," \
                  "reliability_rating,security_rating,sqale_rating"
        
        try:
            async with httpx.AsyncClient(timeout=30, verify=False) as client:
                params = {
                    "component": project_key,
                    "metricKeys": metrics
                }
                if self.branch:
                    params["branch"] = self.branch
                
                resp = await client.get(f"{self.base_url}/api/measures/component", params=params, headers=self.headers)
                resp.raise_for_status()
                data = resp.json()
                
                measures = {}
                rating_map = {"1.0": "A", "2.0": "B", "3.0": "C", "4.0": "D", "5.0": "E"}
                
                for m in data.get("component", {}).get("measures", []):
                    key = m["metric"]
                    val = m.get("value", "0")
                    
                    if key in ("reliability_rating", "security_rating", "sqale_rating"):
                        measures[key] = rating_map.get(val, val)
                    elif key == "sqale_index":
                        measures["tech_debt_minutes"] = int(val)
                        measures["tech_debt_hours"] = round(int(val) / 60, 1)
                    elif key in ("coverage", "duplicated_lines_density"):
                        measures[key] = float(val)
                    else:
                        measures[key] = int(float(val))
                
                return measures
        except Exception as e:
            logger.error(f"Failed to get project summary: {e}")
            return {}

    async def fetch_all_issues(
        self,
        project_key: str,
        types: List[str] = None,
        severities: List[str] = None,
        max_issues: int = 500
    ) -> Dict[str, Any]:
        """
        Step 4: Fetch ALL open issues from SonarQube (paginated).
        
        API: GET /api/issues/search
            ?componentKeys={project_key}
            &branch={branch}
            &types={BUG,VULNERABILITY,CODE_SMELL}     ← comma-separated
            &severities={BLOCKER,CRITICAL,MAJOR}       ← comma-separated
            &statuses=OPEN,CONFIRMED,REOPENED
            &resolved=false
            &ps=500                                    ← page size (max 500)
            &p={page_number}                           ← page number (1-based)
            &additionalFields=_all                     ← get all extra fields
            &facets=types,severities,rules              ← get summary counts
        
        Expected Response (200):
        {
            "total": 104,
            "p": 1,
            "ps": 500,
            "paging": {"pageIndex": 1, "pageSize": 500, "total": 104},
            "issues": [
                {
                    "key": "AYxKEF...",                ← unique issue ID
                    "rule": "java:S2068",              ← Sonar rule ID *** CRITICAL FOR LLM ***
                    "severity": "CRITICAL",            ← BLOCKER|CRITICAL|MAJOR|MINOR|INFO
                    "component": "com.fidelity:portfolio-service:src/main/java/com/.../PaymentService.java",
                    "project": "com.fidelity:portfolio-service",
                    "line": 18,                        ← line number in source file
                    "textRange": {
                        "startLine": 18,
                        "endLine": 18,
                        "startOffset": 4,
                        "endOffset": 65
                    },
                    "message": "Review this hard-coded credential.",  ← human description
                    "type": "VULNERABILITY",           ← BUG|VULNERABILITY|CODE_SMELL
                    "status": "OPEN",                  ← OPEN|CONFIRMED|REOPENED
                    "effort": "10min",                 ← estimated fix time
                    "debt": "10min",
                    "tags": ["cwe", "owasp-a3"],
                    "creationDate": "2024-01-15T10:30:00+0000",
                    "updateDate": "2024-01-15T10:30:00+0000"
                },
                ... more issues
            ],
            "components": [
                {
                    "key": "com.fidelity:portfolio-service:src/main/java/com/.../PaymentService.java",
                    "path": "src/main/java/com/fidelity/payment/service/PaymentService.java",
                    "qualifier": "FIL"
                },
                ... more components
            ],
            "facets": [
                {
                    "property": "types",
                    "values": [
                        {"val": "BUG", "count": 12},
                        {"val": "VULNERABILITY", "count": 5},
                        {"val": "CODE_SMELL", "count": 87}
                    ]
                },
                {
                    "property": "severities",
                    "values": [
                        {"val": "BLOCKER", "count": 3},
                        {"val": "CRITICAL", "count": 15},
                        {"val": "MAJOR", "count": 86}
                    ]
                }
            ]
        }
        
        EXTRACTION LOGIC:
        1. Build a component_key → file_path lookup from the "components" array:
           component_map = {}
           for comp in data["components"]:
               if comp["qualifier"] == "FIL":  # only files
                   component_map[comp["key"]] = comp["path"]
        
        2. For each issue in data["issues"], extract:
           {
               "key": issue["key"],
               "rule": issue["rule"],             ← e.g., "java:S2068"
               "type": issue["type"],             ← e.g., "VULNERABILITY"
               "severity": issue["severity"],     ← e.g., "CRITICAL"
               "message": issue["message"],       ← e.g., "Review this hard-coded credential."
               "line": issue.get("line", 0),
               "start_line": issue.get("textRange", {}).get("startLine", 0),
               "end_line": issue.get("textRange", {}).get("endLine", 0),
               "effort": issue.get("effort", ""),
               "file_path": component_map.get(issue["component"], ""),  ← RESOLVED from components
               "component_key": issue["component"]
           }
        
        3. Extract facets for summary:
           facet_summary = {}
           for facet in data["facets"]:
               facet_summary[facet["property"]] = {v["val"]: v["count"] for v in facet["values"]}
        
        PAGINATION:
        If total > 500, loop:
            page = 1
            all_issues = []
            while len(all_issues) < total and len(all_issues) < max_issues:
                resp = call API with p=page
                all_issues.extend(extracted issues)
                page += 1
                if len(resp["issues"]) < 500: break  # last page
        
        NOTE: SonarQube has a hard limit of 10,000 total results.
        For projects with >10,000 issues, filter by severity (BLOCKER+CRITICAL first).
        
        Returns: {
            "total": 104,
            "issues": [ ... extracted issue dicts ... ],
            "facets": { "types": {"BUG": 12, ...}, "severities": {"CRITICAL": 15, ...} },
            "component_map": { component_key: file_path, ... }
        }
        """
        if types is None:
            types = ["BUG", "VULNERABILITY", "CODE_SMELL"]
        if severities is None:
            severities = ["BLOCKER", "CRITICAL", "MAJOR"]
        
        all_issues = []
        component_map = {}
        facet_summary = {}
        total = 0
        page = 1
        
        try:
            async with httpx.AsyncClient(timeout=60, verify=False) as client:
                while True:
                    params = {
                        "componentKeys": project_key,
                        "types": ",".join(types),
                        "severities": ",".join(severities),
                        "statuses": "OPEN,CONFIRMED,REOPENED",
                        "resolved": "false",
                        "ps": 500,
                        "p": page,
                        "additionalFields": "_all",
                    }
                    if self.branch:
                        params["branch"] = self.branch
                    if page == 1:
                        params["facets"] = "types,severities,rules"
                    
                    resp = await client.get(f"{self.base_url}/api/issues/search", params=params, headers=self.headers)
                    resp.raise_for_status()
                    data = resp.json()
                    
                    if page == 1:
                        total = data.get("total", 0)
                        # Build component map
                        for comp in data.get("components", []):
                            if comp.get("qualifier") == "FIL":
                                component_map[comp["key"]] = comp.get("path", "")
                        # Extract facets
                        for facet in data.get("facets", []):
                            facet_summary[facet["property"]] = {
                                v["val"]: v["count"] for v in facet.get("values", [])
                            }
                    else:
                        # Subsequent pages also have components
                        for comp in data.get("components", []):
                            if comp.get("qualifier") == "FIL":
                                component_map[comp["key"]] = comp.get("path", "")
                    
                    for issue in data.get("issues", []):
                        all_issues.append({
                            "key": issue.get("key", ""),
                            "rule": issue.get("rule", ""),
                            "type": issue.get("type", ""),
                            "severity": issue.get("severity", ""),
                            "message": issue.get("message", ""),
                            "line": issue.get("line", 0),
                            "start_line": issue.get("textRange", {}).get("startLine", 0),
                            "end_line": issue.get("textRange", {}).get("endLine", 0),
                            "effort": issue.get("effort", ""),
                            "file_path": component_map.get(issue.get("component", ""), ""),
                            "component_key": issue.get("component", ""),
                            "tags": issue.get("tags", []),
                        })
                    
                    # Check if we need more pages
                    if len(data.get("issues", [])) < 500:
                        break  # Last page
                    if len(all_issues) >= max_issues:
                        break  # Hit our limit
                    page += 1
                    if page > 20:
                        break  # Safety limit (10,000 issues max)
        
        except Exception as e:
            logger.error(f"Failed to fetch issues: {e}")
            return {"total": 0, "issues": [], "facets": {}, "component_map": {}, "error": str(e)}
        
        return {
            "total": total,
            "issues": all_issues[:max_issues],
            "facets": facet_summary,
            "component_map": component_map,
        }

    async def get_rule_details(self, rule_key: str) -> Dict[str, Any]:
        """
        Optional Step: Get detailed description of a Sonar rule.
        Useful for building better LLM prompts.
        
        API: GET /api/rules/show?key={rule_key}
        
        Example: GET /api/rules/show?key=java:S2068
        
        Expected Response (200):
        {
            "rule": {
                "key": "java:S2068",
                "name": "Credentials should not be hard-coded",
                "htmlDesc": "<p>Hard-coded credentials are...</p>",
                "severity": "CRITICAL",
                "type": "VULNERABILITY",
                "tags": ["cwe", "owasp-a3"],
                "langName": "Java"
            }
        }
        
        EXTRACT: rule.name and rule.htmlDesc (strip HTML tags for LLM context)
        
        Returns: {"name": "...", "description": "..."}
        """
        try:
            async with httpx.AsyncClient(timeout=15, verify=False) as client:
                resp = await client.get(
                    f"{self.base_url}/api/rules/show",
                    params={"key": rule_key},
                    headers=self.headers
                )
                if resp.status_code == 200:
                    rule = resp.json().get("rule", {})
                    # Strip HTML tags from description
                    import re
                    desc = re.sub(r'<[^>]+>', '', rule.get("htmlDesc", ""))
                    return {"name": rule.get("name", ""), "description": desc[:500]}
        except Exception:
            pass
        return {"name": rule_key, "description": ""}
```

---

## FILE 2: backend/app/prompts/sonar_fix/system.txt (REPLACE)

```text
You are a senior Java developer specializing in SonarQube code quality remediation.
Your task is to fix specific SonarQube issues in Java source code.

## STRICT RULES:
1. Fix ONLY the specific issues listed. Do NOT refactor unrelated code.
2. Maintain the EXACT same public API — do not change method signatures, class names, return types, or parameter types.
3. Return the COMPLETE fixed Java file starting with the package declaration. Not just snippets.
4. Do NOT add markdown fences (```). Return raw Java code only.
5. Do NOT add explanatory comments outside the code.

## FIX STRATEGIES BY RULE:

### VULNERABILITIES:
- java:S2068 (Hard-coded credentials): Replace with @Value("${property}") Spring injection or System.getenv(). Add a comment noting the property needs to be in application.yml.
- java:S4790 (Weak crypto MD5/SHA1): Replace with MessageDigest.getInstance("SHA-256").
- java:S5332 (HTTP instead of HTTPS): Change http:// to https://.
- java:S2647 (No timeout on HTTP): Add .setConnectTimeout() and .setReadTimeout().
- java:S2095 (Resource leak): Use try-with-resources.
- java:S3649 (SQL injection): Use PreparedStatement with ? parameters or JPA parameterized queries.

### BUGS:
- java:S2259 (Null pointer): Add null checks before dereferencing. Use Optional if appropriate.
- java:S1168 (Return null from method returning collection): Return empty collection instead.
- java:S2225 (toString on null): Add null check.

### CODE SMELLS:
- java:S106 (System.out/err): Replace with private static final Logger logger = LoggerFactory.getLogger(ClassName.class); and logger.info/error/warn. Add import for org.slf4j.Logger and org.slf4j.LoggerFactory.
- java:S1148 (printStackTrace): Replace with logger.error("message", exception).
- java:S1068 (Unused field): Remove the unused field.
- java:S1854 (Unused assignment): Remove or use the variable.
- java:S109 (Magic number): Extract to a named constant with descriptive name.
- java:S2221 (Catch generic Exception): Catch specific exceptions.
- java:S1192 (Duplicated string): Extract to a constant.
- java:S1125 (Unnecessary boolean literal): Simplify.
- java:S1481 (Unused local variable): Remove.
- java:S1135 (TODO/FIXME): Remove or implement.

### COGNITIVE COMPLEXITY (java:S3776) — SPECIAL HANDLING:
This is the hardest one. The message says "Refactor this method to reduce its Cognitive Complexity from X to the Y allowed."
Strategies:
a) Extract nested if/else blocks into private helper methods with descriptive names
b) Replace nested conditions with early returns (guard clauses)
c) Extract complex boolean expressions into named variables: boolean isEligible = (age > 18 && active);
d) Replace if-else chains with switch expressions or Map lookups
e) DO NOT change external behavior — same inputs must produce same outputs
f) If you cannot get complexity below the threshold, reduce it as much as possible
g) Keep extracted methods in the SAME class as private methods

### STRING COMPARISON:
- java:S1698 (== used for String comparison): Replace == with .equals() for String comparisons.

## OUTPUT FORMAT:
Return ONLY the complete fixed Java source code.
Start with: package com.xxx.yyy;
End with the closing brace of the class.
Include ALL imports (including any new ones you add like SLF4J Logger).
```

---

## FILE 3: backend/app/prompts/sonar_fix/fix_issues.txt (REPLACE)

```text
Fix the following SonarQube issues in this Java file.

== FILE PATH ==
{file_path}

== ISSUES TO FIX ({issue_count} issues) ==
{issues_formatted}

== SPECIAL INSTRUCTIONS ==
{special_instructions}

== RULES ==
1. Fix ALL {issue_count} listed issues in this single file.
2. Return the COMPLETE fixed Java file — not just the changed parts.
3. Start with the package declaration. Include all imports.
4. Do NOT wrap in markdown code fences.
5. Do NOT change any public method signatures or class structure.
6. Make sure the code compiles after your changes.
7. If fixing java:S106 (System.out), add these imports:
   import org.slf4j.Logger;
   import org.slf4j.LoggerFactory;
   And add this field:
   private static final Logger logger = LoggerFactory.getLogger({class_name}.class);

== SOURCE CODE (to fix) ==
{source_code}
```

---

## FILE 4: backend/app/prompts/sonar_fix/scan_local.txt (NEW — for Mode B when no Sonar connection)

```text
Analyze this Java file for SonarQube-style code quality issues.
Look for: bugs, vulnerabilities, and code smells.

For each issue found, return a JSON array:
[
  {
    "rule": "java:SXXXX",
    "type": "BUG|VULNERABILITY|CODE_SMELL",
    "severity": "BLOCKER|CRITICAL|MAJOR|MINOR",
    "line": <line_number>,
    "message": "<description of the issue>"
  }
]

Common issues to look for:
- Hardcoded passwords/credentials (java:S2068)
- System.out.println usage (java:S106)
- printStackTrace calls (java:S1148)
- Generic exception catch (java:S2221)
- Null pointer risks (java:S2259)
- Resource leaks — unclosed streams/connections (java:S2095)
- MD5/SHA1 weak crypto (java:S4790)
- SQL injection (java:S3649)
- Unused variables and fields (java:S1068, java:S1481)
- Magic numbers (java:S109)
- Empty if/catch blocks (java:S108)
- String comparison with == (java:S1698)
- Cognitive complexity > 15 (java:S3776)
- HTTP instead of HTTPS (java:S5332)

Return ONLY the JSON array. No other text.

== SOURCE FILE ==
{file_path}

{source_code}
```

---

## FILE 5: backend/app/services/solutions/sonar_fix/solution.py (REWRITE)

This is the MAIN file. Here is the complete pipeline logic:

```python
"""
Sonar Code Remediation Solution — Full Pipeline.

Two execution modes:
Mode A (scan_only=True): Connect to SonarQube → fetch issues → return plan
Mode B (scan_only=False): Fix selected issues → compile → validate → return fixed project

Dual source modes:
1. Sonar API mode: Uses real SonarQube server for issue detection
2. Local AI mode: When no Sonar URL provided, uses LLM to detect issues
"""

import re
import json
from pathlib import Path
from typing import List, Dict, Optional, Any, Tuple
from loguru import logger

from app.core.job_manager import Job
from app.models.schemas import JobStatus
from app.services.shared.llm_client import llm_client
from app.services.shared.sonar_client import SonarQubeClient
from app.services.shared.maven_runner import MavenRunner
from app.services.shared.project_analyzer import ProjectAnalyzer
from app.services.shared.prompt_manager import prompt_manager


class SonarFixSolution:

    def __init__(self, config: Optional[Dict] = None):
        self.config = config or {}

    async def execute(self, job: Job, project_dir: str, model_id: Optional[str] = None) -> Dict[str, Any]:
        """
        Main entry point. Called by the job router.
        
        Config fields from UI:
            scan_only: bool — if True, only scan and return plan. if False, fix issues.
            sonar_url: str — e.g., "https://sonar.fmr.com"
            sonar_token: str — Bearer token
            sonar_project_key: str — e.g., "com.fidelity:portfolio-service"
            branch: str — default "main"
            fix_categories: list — ["BUG", "VULNERABILITY", "CODE_SMELL"]
            severity_filter: list — ["BLOCKER", "CRITICAL", "MAJOR"]
            max_issues_to_fix: int — default 50
            selected_issues: list — issue keys to fix (only in Phase B)
        """
        llm_client.reset_tracking()
        
        scan_only = self.config.get("scan_only", True)
        sonar_url = self.config.get("sonar_url", "")
        sonar_token = self.config.get("sonar_token", "")
        project_key = self.config.get("sonar_project_key", "")
        branch = self.config.get("branch", "main")
        fix_categories = self.config.get("fix_categories", ["BUG", "VULNERABILITY", "CODE_SMELL"])
        severity_filter = self.config.get("severity_filter", ["BLOCKER", "CRITICAL", "MAJOR"])
        max_issues = self.config.get("max_issues_to_fix", 50)
        selected_issue_keys = self.config.get("selected_issues", [])  # For Phase B
        
        use_sonar_api = bool(sonar_url and sonar_token and project_key)
        
        # ==============================
        # PHASE A: SCAN
        # ==============================
        if scan_only or not selected_issue_keys:
            return await self._phase_scan(
                job, project_dir, model_id,
                use_sonar_api, sonar_url, sonar_token, project_key, branch,
                fix_categories, severity_filter, max_issues
            )
        
        # ==============================
        # PHASE B: FIX
        # ==============================
        return await self._phase_fix(
            job, project_dir, model_id,
            use_sonar_api, sonar_url, sonar_token, project_key, branch,
            selected_issue_keys, max_issues
        )

    async def _phase_scan(
        self, job, project_dir, model_id,
        use_sonar_api, sonar_url, sonar_token, project_key, branch,
        fix_categories, severity_filter, max_issues
    ) -> Dict[str, Any]:
        """
        Phase A: Scan for issues and return the plan.
        
        If Sonar API available:
            1. Validate connection
            2. Validate project
            3. Get project summary (measures)
            4. Fetch all issues (paginated)
            5. Return categorized plan
        
        If no Sonar API (local mode):
            1. Scan all Java files in ZIP
            2. Use pattern matching + LLM to detect issues
            3. Return categorized plan
        """
        
        if use_sonar_api:
            # --- SONAR API MODE ---
            
            # Step 1: Validate connection
            job.update(status=JobStatus.ANALYZING, progress=5.0, phase="Connecting to SonarQube...")
            client = SonarQubeClient(sonar_url, sonar_token, branch)
            conn = await client.validate_connection()
            if not conn.get("connected"):
                return {"error": conn.get("error", "Connection failed"), "phase": "scan"}
            
            # Step 2: Validate project
            job.update(progress=10.0, phase="Validating project access...")
            proj = await client.validate_project(project_key)
            if not proj.get("found"):
                return {"error": proj.get("error", "Project not found"), "phase": "scan"}
            
            # Step 3: Get summary
            job.update(progress=20.0, phase="Fetching project metrics...")
            summary = await client.get_project_summary(project_key)
            
            # Step 4: Fetch all issues
            job.update(progress=30.0, phase="Fetching issues from SonarQube...")
            issue_data = await client.fetch_all_issues(
                project_key, types=fix_categories,
                severities=severity_filter, max_issues=max_issues
            )
            
            if issue_data.get("error"):
                return {"error": issue_data["error"], "phase": "scan"}
            
            issues = issue_data["issues"]
            facets = issue_data["facets"]
            
        else:
            # --- LOCAL AI ANALYSIS MODE ---
            job.update(status=JobStatus.ANALYZING, progress=10.0, phase="Scanning project locally (no Sonar connection)...")
            summary = {}
            issues = await self._local_scan(job, project_dir, model_id)
            facets = self._build_facets_from_issues(issues)
        
        # Step 5: Group by file and categorize
        job.update(progress=80.0, phase="Building fix plan...")
        issues_by_file = self._group_issues_by_file(issues)
        
        # Build the plan result
        plan = {
            "phase": "scan",
            "scan_only": True,
            "sonar_connected": use_sonar_api,
            "project_summary": summary,
            "total_issues": len(issues),
            "issues": issues,  # Full list for UI to display
            "issues_by_file": {
                fp: {
                    "file_path": fp,
                    "issue_count": len(file_issues),
                    "types": list(set(i["type"] for i in file_issues)),
                    "severities": list(set(i["severity"] for i in file_issues)),
                    "issues": file_issues
                }
                for fp, file_issues in issues_by_file.items()
            },
            "facets": facets,
            "files_affected": len(issues_by_file),
            "estimated_effort": self._calculate_effort(issues),
        }
        
        job.update(status=JobStatus.COMPLETED, progress=100.0, phase="Scan complete")
        return plan

    async def _phase_fix(
        self, job, project_dir, model_id,
        use_sonar_api, sonar_url, sonar_token, project_key, branch,
        selected_issue_keys, max_issues
    ) -> Dict[str, Any]:
        """
        Phase B: Fix the selected issues.
        
        For each file with selected issues:
            1. Read source code from the uploaded project ZIP
            2. Collect all issues for this file
            3. Build LLM prompt: system prompt + issues list + source code
            4. Call LLM via gateway API (llm_client.generate)
            5. Write fixed code to file
            6. Run: mvn compile -DskipTests
            7. If compile FAILS → send error to LLM → retry (max 3 attempts)
            8. If compile PASSES → keep fix, move to next file
            9. If all 3 attempts fail → ROLLBACK to original source, mark as failed
        
        After all files:
            10. Run: mvn clean compile (full build)
            11. Run: mvn test (ensure existing tests still pass)
            12. Package result
        """
        
        # First, we need the issues. Re-fetch from Sonar or use cached issues from config
        cached_issues = self.config.get("cached_issues", [])
        if not cached_issues and use_sonar_api:
            job.update(status=JobStatus.ANALYZING, progress=5.0, phase="Re-fetching issue details...")
            client = SonarQubeClient(sonar_url, sonar_token, branch)
            issue_data = await client.fetch_all_issues(project_key, max_issues=max_issues)
            cached_issues = issue_data.get("issues", [])
        
        # Filter to only selected issues
        if selected_issue_keys:
            selected_issues = [i for i in cached_issues if i.get("key") in selected_issue_keys]
        else:
            selected_issues = cached_issues[:max_issues]
        
        if not selected_issues:
            return {"error": "No issues to fix", "phase": "fix"}
        
        # Analyze project for Maven
        job.update(status=JobStatus.ANALYZING, progress=10.0, phase="Analyzing project...")
        analyzer = ProjectAnalyzer(project_dir)
        project_info = analyzer.analyze()
        maven = MavenRunner(project_dir, project_info.java_version)
        
        # Validate initial build
        job.update(progress=15.0, phase="Validating project compiles...")
        initial_build_ok, initial_build_err = await maven.compile_only()
        # Note: Even if initial build fails, we still try to fix — the fixes might resolve build issues too
        
        # Group selected issues by file
        issues_by_file = self._group_issues_by_file(selected_issues)
        
        # Fix each file
        job.update(status=JobStatus.GENERATING, progress=20.0, phase="Generating fixes...")
        
        file_results = []
        fixed_count = 0
        failed_count = 0
        total_files = len(issues_by_file)
        
        system_prompt = prompt_manager.get_prompt("sonar_fix", "system")
        
        for idx, (file_path, file_issues) in enumerate(issues_by_file.items()):
            progress = 20.0 + (60.0 * (idx + 1) / total_files)
            file_name = Path(file_path).stem
            job.update(progress=progress, phase=f"Fixing: {file_name} ({idx+1}/{total_files})")
            
            result = await self._fix_single_file(
                project_dir=project_dir,
                file_path=file_path,
                issues=file_issues,
                system_prompt=system_prompt,
                maven=maven,
                model_id=model_id,
            )
            
            file_results.append(result)
            if result["success"]:
                fixed_count += result["issues_fixed"]
            else:
                failed_count += len(file_issues)
        
        # Final build validation
        job.update(progress=85.0, phase="Running final build validation...")
        final_compile_ok, _ = await maven.compile_only()
        
        # Run tests
        job.update(progress=90.0, phase="Running existing tests...")
        tests_ok = False
        test_output = ""
        if final_compile_ok:
            tests_ok, test_output = await maven.run_tests()
        
        # Calculate results
        usage = llm_client.get_usage_summary()
        total_effort_saved = sum(
            self._parse_effort(i.get("effort", "0min"))
            for i in selected_issues
            if any(r["success"] and i["file_path"] == r["file_path"] for r in file_results)
        )
        
        result = {
            "phase": "fix",
            "scan_only": False,
            "total_selected": len(selected_issues),
            "issues_fixed": fixed_count,
            "issues_failed": failed_count,
            "files_modified": sum(1 for r in file_results if r["success"]),
            "files_failed": sum(1 for r in file_results if not r["success"]),
            "final_build_passed": final_compile_ok,
            "tests_passed": tests_ok,
            "test_output_snippet": test_output[:500] if test_output else "",
            "file_results": file_results,
            "effort_saved_minutes": total_effort_saved,
            "total_tokens_used": usage.get("total_tokens", 0),
            "total_llm_calls": usage.get("total_calls", 0),
            "estimated_cost": usage.get("total_cost", 0),
        }
        
        job.update(status=JobStatus.COMPLETED, progress=100.0, phase="Fix complete")
        return result

    async def _fix_single_file(
        self, project_dir: str, file_path: str, issues: List[Dict],
        system_prompt: str, maven: MavenRunner, model_id: Optional[str],
    ) -> Dict[str, Any]:
        """
        Fix all issues in a single file. Retry up to 3 times.
        
        Steps:
        1. Read original source from project ZIP
        2. Build prompt with all issues for this file
        3. Call LLM
        4. Write fixed code
        5. mvn compile → if fails, send error to LLM → retry
        6. If 3 attempts fail → rollback
        """
        result = {
            "file_path": file_path,
            "file_name": Path(file_path).stem,
            "issue_count": len(issues),
            "issues_fixed": 0,
            "success": False,
            "attempts": 0,
            "error": "",
            "tokens_used": 0,
            "cost": 0.0,
        }
        
        full_path = Path(project_dir) / file_path
        if not full_path.exists():
            result["error"] = f"File not found in project: {file_path}"
            return result
        
        original_source = full_path.read_text(encoding="utf-8", errors="replace")
        class_name = Path(file_path).stem
        
        # Format issues for the prompt
        issues_formatted = "\n".join(
            f"{idx+1}. [{issue['rule']}] {issue['severity']} (line {issue.get('line', '?')}): {issue['message']}"
            for idx, issue in enumerate(issues)
        )
        
        # Special instructions for cognitive complexity
        special_instructions = ""
        has_complexity = any(i["rule"] == "java:S3776" for i in issues)
        if has_complexity:
            special_instructions = """
COGNITIVE COMPLEXITY SPECIAL HANDLING:
- Extract nested conditions into private helper methods
- Use early returns (guard clauses) instead of deep nesting
- Extract complex boolean expressions into named variables
- Replace if-else chains with switch or Map lookups
- Keep all extracted methods in the same class
- Do NOT change method signatures or return types
"""
        
        user_prompt = prompt_manager.render_prompt(
            "sonar_fix", "fix_issues",
            file_path=file_path,
            issue_count=str(len(issues)),
            issues_formatted=issues_formatted,
            special_instructions=special_instructions,
            class_name=class_name,
            source_code=original_source,
        )
        
        # Attempt loop: try up to 3 times
        for attempt in range(1, 4):
            result["attempts"] = attempt
            
            try:
                # Call LLM via Gateway API
                llm_response = await llm_client.generate(
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    model_id=model_id or "gpt-4o",  # or "claude-sonnet" for better code refactoring
                    temperature=0.0,  # Deterministic
                    max_tokens=8192,  # Source files can be long
                )
                
                result["tokens_used"] += llm_response.get("tokens", {}).get("total", 0)
                result["cost"] += llm_response.get("cost", 0)
                
                fixed_code = self._extract_java_code(llm_response["content"])
                if not fixed_code:
                    user_prompt = f"Your previous response was not valid Java code. Please return ONLY the complete fixed Java file.\n\nOriginal:\n{original_source}\n\nIssues:\n{issues_formatted}"
                    continue
                
                # Write fixed code
                full_path.write_text(fixed_code, encoding="utf-8")
                
                # Compile check
                compile_ok, compile_error = await maven.compile_only()
                
                if compile_ok:
                    result["success"] = True
                    result["issues_fixed"] = len(issues)
                    logger.info(f"✅ Fixed: {file_path} — {len(issues)} issues ({attempt} attempt(s))")
                    return result
                else:
                    # Compile failed — build retry prompt with error
                    logger.warning(f"❌ Compile failed for {file_path} (attempt {attempt}): {compile_error[:200]}")
                    
                    # Restore original for next attempt
                    full_path.write_text(original_source, encoding="utf-8")
                    
                    # Build retry prompt
                    user_prompt = f"""Your previous fix caused compilation errors. Please fix the issues AND make the code compile.

COMPILATION ERRORS:
{compile_error[:2000]}

ORIGINAL SOURCE CODE:
{original_source}

ISSUES TO FIX:
{issues_formatted}

Return the COMPLETE fixed and COMPILABLE Java file. Do not use any imports or classes that don't exist in the project."""
                    
            except Exception as e:
                logger.error(f"Error fixing {file_path}: {e}")
                result["error"] = str(e)
                # Restore original
                full_path.write_text(original_source, encoding="utf-8")
        
        # All 3 attempts failed — rollback
        full_path.write_text(original_source, encoding="utf-8")
        result["error"] = f"Failed to fix after {result['attempts']} attempts. File rolled back to original."
        logger.warning(f"⚠️ Rolled back: {file_path}")
        return result

    async def _local_scan(self, job: Job, project_dir: str, model_id: Optional[str]) -> List[Dict]:
        """
        Local AI analysis mode — when no SonarQube connection.
        Scans Java files using pattern matching + LLM.
        """
        issues = []
        root = Path(project_dir)
        java_files = [f for f in root.rglob("*.java") if "target/" not in str(f) and "test/" not in str(f)]
        
        for idx, java_file in enumerate(java_files):
            progress = 10.0 + (60.0 * (idx + 1) / len(java_files))
            job.update(progress=progress, phase=f"Analyzing: {java_file.stem}")
            
            content = java_file.read_text(encoding="utf-8", errors="replace")
            rel_path = str(java_file.relative_to(root))
            
            # Pattern-based detection (fast, no LLM needed)
            issues.extend(self._pattern_scan(content, rel_path))
            
            # Optional: LLM-based deep scan for complex issues
            # Only for files > 50 lines to avoid wasting tokens on simple files
            if content.count("\n") > 50 and model_id:
                try:
                    scan_prompt = prompt_manager.render_prompt(
                        "sonar_fix", "scan_local",
                        file_path=rel_path,
                        source_code=content[:6000],
                    )
                    resp = await llm_client.generate(
                        system_prompt="You are a SonarQube code analyzer. Return ONLY a JSON array of issues.",
                        user_prompt=scan_prompt,
                        model_id=model_id,
                        temperature=0.0,
                        max_tokens=2048,
                    )
                    # Parse JSON response
                    json_text = resp["content"].strip()
                    if json_text.startswith("```"): json_text = json_text.split("\n", 1)[1]
                    if json_text.endswith("```"): json_text = json_text.rsplit("```", 1)[0]
                    llm_issues = json.loads(json_text)
                    for issue in llm_issues:
                        issue["file_path"] = rel_path
                        issue["key"] = f"LOCAL-{len(issues)}"
                        issues.append(issue)
                except Exception as e:
                    logger.debug(f"LLM scan failed for {rel_path}: {e}")
        
        return issues

    def _pattern_scan(self, content: str, file_path: str) -> List[Dict]:
        """Fast pattern-based issue detection."""
        issues = []
        lines = content.split("\n")
        
        patterns = [
            (r'System\.(out|err)\.print', "java:S106", "CODE_SMELL", "MAJOR", "Replace System.out/err with a logger"),
            (r'\.printStackTrace\(\)', "java:S1148", "CODE_SMELL", "MAJOR", "Use a logger instead of printStackTrace"),
            (r'catch\s*\(\s*Exception\s+\w+\s*\)', "java:S2221", "CODE_SMELL", "MAJOR", "Catch specific exception types"),
            (r'password\s*=\s*"[^"]+"|PASSWORD\s*=\s*"[^"]+"', "java:S2068", "VULNERABILITY", "CRITICAL", "Hard-coded credential detected"),
            (r'secret\s*=\s*"[^"]+"|SECRET\s*=\s*"[^"]+"', "java:S2068", "VULNERABILITY", "CRITICAL", "Hard-coded secret detected"),
            (r'api[_-]?key\s*=\s*"[^"]+"', "java:S2068", "VULNERABILITY", "CRITICAL", "Hard-coded API key detected"),
            (r'getInstance\(\s*"MD5"\s*\)', "java:S4790", "VULNERABILITY", "CRITICAL", "MD5 is not collision resistant"),
            (r'getInstance\(\s*"SHA-?1"\s*\)', "java:S4790", "VULNERABILITY", "CRITICAL", "SHA-1 is not collision resistant"),
            (r'http://', "java:S5332", "VULNERABILITY", "MAJOR", "Use HTTPS instead of HTTP"),
            (r'==\s*"[^"]*"|"[^"]*"\s*==', "java:S1698", "BUG", "MAJOR", "Use .equals() for String comparison"),
        ]
        
        for i, line in enumerate(lines, 1):
            for pattern, rule, issue_type, severity, message in patterns:
                if re.search(pattern, line, re.IGNORECASE):
                    # Skip if it's in a comment or test file
                    stripped = line.strip()
                    if stripped.startswith("//") or stripped.startswith("*"):
                        continue
                    if "test/" in file_path.lower():
                        continue
                    issues.append({
                        "key": f"LOCAL-{file_path}-{i}",
                        "rule": rule,
                        "type": issue_type,
                        "severity": severity,
                        "message": message,
                        "line": i,
                        "start_line": i,
                        "end_line": i,
                        "effort": "5min",
                        "file_path": file_path,
                        "component_key": "",
                        "tags": [],
                    })
        
        return issues

    def _group_issues_by_file(self, issues: List[Dict]) -> Dict[str, List[Dict]]:
        """Group issues by file path."""
        grouped = {}
        for issue in issues:
            fp = issue.get("file_path", "")
            if fp:
                grouped.setdefault(fp, []).append(issue)
        return grouped

    def _build_facets_from_issues(self, issues: List[Dict]) -> Dict:
        """Build facet summary from local issues."""
        type_counts = {}
        severity_counts = {}
        for issue in issues:
            t = issue.get("type", "CODE_SMELL")
            s = issue.get("severity", "MAJOR")
            type_counts[t] = type_counts.get(t, 0) + 1
            severity_counts[s] = severity_counts.get(s, 0) + 1
        return {"types": type_counts, "severities": severity_counts}

    def _calculate_effort(self, issues: List[Dict]) -> int:
        """Calculate total effort in minutes."""
        return sum(self._parse_effort(i.get("effort", "0min")) for i in issues)

    def _parse_effort(self, effort: str) -> int:
        """Parse effort string like '10min' or '2h' into minutes."""
        if not effort:
            return 5
        effort = effort.lower().strip()
        if "h" in effort:
            return int(re.search(r'\d+', effort).group()) * 60
        if "min" in effort:
            return int(re.search(r'\d+', effort).group())
        try:
            return int(effort)
        except:
            return 5

    def _extract_java_code(self, content: str) -> Optional[str]:
        """Extract Java code from LLM response, stripping markdown fences."""
        content = content.strip()
        if content.startswith("```java"):
            content = content[7:]
        elif content.startswith("```"):
            content = content[3:]
        if content.endswith("```"):
            content = content[:-3]
        content = content.strip()
        
        # Validate it looks like Java
        if "class " in content or "interface " in content or "enum " in content:
            return content
        return None
```

---

## FILE 6: backend/app/models/schemas.py — UPDATE SonarFixConfig

Find the existing SonarFixConfig and SonarFixResult classes and REPLACE with:

```python
class SonarFixConfig(BaseModel):
    """Configuration for sonar remediation."""
    scan_only: bool = True  # True = Phase A (scan), False = Phase B (fix)
    sonar_url: str = ""
    sonar_token: str = ""
    sonar_project_key: str = ""
    branch: str = "main"
    fix_categories: List[str] = ["BUG", "VULNERABILITY", "CODE_SMELL"]
    severity_filter: List[str] = ["BLOCKER", "CRITICAL", "MAJOR"]
    max_issues_to_fix: int = 50
    selected_issues: List[str] = []  # Issue keys selected by user for Phase B
    cached_issues: List[Dict[str, Any]] = []  # Issues from Phase A cached for Phase B


class SonarFixResult(BaseModel):
    """Result of sonar remediation."""
    phase: str = "scan"  # "scan" or "fix"
    scan_only: bool = True
    sonar_connected: bool = False
    project_summary: Dict[str, Any] = {}
    total_issues: int = 0
    issues: List[Dict[str, Any]] = []
    issues_by_file: Dict[str, Any] = {}
    facets: Dict[str, Any] = {}
    files_affected: int = 0
    estimated_effort: int = 0
    # Fix-phase fields:
    total_selected: int = 0
    issues_fixed: int = 0
    issues_failed: int = 0
    files_modified: int = 0
    files_failed: int = 0
    final_build_passed: bool = False
    tests_passed: bool = False
    file_results: List[Dict[str, Any]] = []
    effort_saved_minutes: int = 0
    total_tokens_used: int = 0
    total_llm_calls: int = 0
    estimated_cost: float = 0.0
```

---

## FILE 7: frontend/src/app/features/sonar-fix/sonar-fix.component.ts — REWRITE

The UI needs TWO states:

STATE 1 — Input + Scan:
- File upload zone
- SonarQube connection fields (URL, Token, Project Key, Branch)
- Category checkboxes (Bug, Vulnerability, Code Smell)
- Severity checkboxes (Blocker, Critical, Major)
- Model selector
- "Scan" button → calls API with { scan_only: true }
- Shows progress while scanning

STATE 2 — Issue Plan (after scan completes):
- Project Summary card: bugs, vulns, smells, coverage, tech debt
- Issue table grouped by file:
  | File | Type | Severity | Rule | Message | Line | ☐ |
- Filter buttons: [All] [Bugs] [Vulnerabilities] [Code Smells]
- "Select All" checkbox per file, per category
- Counter: "32 of 104 selected"
- "Fix Selected Issues" button → calls API with { scan_only: false, selected_issues: [...] }
- Shows progress while fixing

STATE 3 — Results (after fix completes):
- Before/After comparison cards
- Build status badge (PASS/FAIL)
- Tests status badge
- Per-file results: file name, success/failed, attempts, tokens
- Download button for fixed project
- Cost and ROI metrics
- "Run Another" button

IMPORTANT: When calling the API for Phase B (fix), pass the FULL cached_issues 
array from Phase A result back in the config. This avoids re-fetching from Sonar.

Use these API calls:
- Scan:  POST /api/v1/jobs/submit with config_json = { scan_only: true, sonar_url: ..., ... }
- Fix:   POST /api/v1/jobs/submit with config_json = { scan_only: false, selected_issues: [...], cached_issues: [...] }
- Poll:  GET /api/v1/jobs/{job_id}/status
- Download: GET /api/v1/jobs/{job_id}/download

Follow the exact same patterns and styling as the existing sonar-fix.component.ts.
Reuse: app-file-upload, app-job-progress components.

---

## TESTING INSTRUCTIONS

1. Test with the `legacy-payment-service.zip` sample project (provided separately)
   - It has 20+ deliberate issues: hardcoded passwords, System.out, MD5, SQL injection, etc.
   
2. For LOCAL MODE (no Sonar): Upload ZIP, leave Sonar fields empty → should detect issues via pattern matching

3. For SONAR API MODE: Use any accessible SonarQube instance:
   - Public: https://next.sonarqube.com/sonarqube (limited — read-only, needs public project)
   - FMR Internal: https://sonar.fmr.com or https://sonar-qa.fmr.com

4. Verify:
   - Scan returns categorized issues grouped by file
   - Fix generates compilable code
   - Rollback works when LLM fix fails compilation
   - Final build passes after fixes
   - Download ZIP contains fixed files

---

## CRITICAL REMINDERS FOR COPILOT

1. DO NOT break existing solutions. This is an UPDATE to sonar_fix only.
2. The sonar_client.py is a NEW file in services/shared/.
3. Reuse existing: llm_client.generate(), maven_runner.compile_only(), prompt_manager.render_prompt()
4. The LLM call goes through the FMR GenAI Gateway (already configured in llm_client.py).
5. All API calls to SonarQube use: Authorization: Bearer {token} header.
6. SSL verify=False for internal servers with self-signed certs.
7. Handle pagination: SonarQube max 500 per page, loop until all fetched.
8. The fix loop MUST backup original files and rollback on failure.
9. Temperature=0.0 for code fixes (deterministic output).
10. max_tokens=8192 for fix calls (source files can be long).
