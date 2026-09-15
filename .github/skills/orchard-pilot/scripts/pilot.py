#!/usr/bin/env python3

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def fetch_json(base_url: str, path: str, timeout: float) -> object:
    url = f"{base_url.rstrip('/')}{path}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:512]
        raise RuntimeError(f"{url} returned HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Could not reach {url}: {error.reason}") from error


def main() -> int:
    parser = argparse.ArgumentParser(description="Read Orchard's deterministic AI pilot surface.")
    parser.add_argument(
        "command",
        choices=("preflight", "status", "atlas"),
        nargs="?",
        default="preflight",
        help="Verify Orchard/provider readiness, read compact status, or read the state atlas.",
    )
    parser.add_argument("domain", nargs="?", help="Atlas domain ID to return.")
    parser.add_argument(
        "--base-url",
        default=os.environ.get("ORCHARD_BASE_URL", "http://127.0.0.1:8085"),
        help="Orchard base URL (default: ORCHARD_BASE_URL or http://127.0.0.1:8085).",
    )
    parser.add_argument("--timeout", type=float, default=10.0, help="HTTP timeout in seconds.")
    args = parser.parse_args()

    try:
        if args.command == "preflight":
            if args.domain:
                parser.error("preflight does not accept a domain")
            status = fetch_json(args.base_url, "/api/pilot/status", args.timeout)
            catalog = fetch_json(args.base_url, "/api/model-providers", args.timeout)
            inspection = fetch_json(args.base_url, "/api/model-providers/inspection", args.timeout)
            if not isinstance(status, dict) or not isinstance(catalog, dict) or not isinstance(inspection, list):
                raise RuntimeError("Orchard returned an invalid pilot or provider projection")
            model_actions = [
                action
                for action in status.get("authorizedActions", [])
                if str(action.get("costClass", "")).startswith("MODEL")
            ]
            enabled_endpoints = [endpoint for endpoint in catalog.get("endpoints", []) if endpoint.get("enabled", True)]
            reachable = [endpoint for endpoint in inspection if endpoint.get("reachable") is True]
            issues = []
            if model_actions and not enabled_endpoints:
                issues.append("A model-backed action is authorized but no model endpoint is enabled.")
            if model_actions and not reachable:
                issues.append("A model-backed action is authorized but no configured provider is reachable.")
            result = {
                "ready": not issues,
                "baseUrl": args.base_url.rstrip("/"),
                "pilotState": status.get("state"),
                "modelActionRequired": bool(model_actions),
                "authorizedModelActions": [action.get("id") for action in model_actions],
                "configuredProviders": [
                    {
                        "endpointId": endpoint.get("endpointId"),
                        "protocol": endpoint.get("protocol"),
                        "baseUrl": endpoint.get("baseUrl"),
                    }
                    for endpoint in enabled_endpoints
                ],
                "providerInspection": inspection,
                "issues": issues,
            }
        elif args.command == "status":
            if args.domain:
                parser.error("status does not accept a domain")
            result = fetch_json(args.base_url, "/api/pilot/status", args.timeout)
        else:
            atlas = fetch_json(args.base_url, "/api/pilot/state-atlas", args.timeout)
            if not isinstance(atlas, list):
                raise RuntimeError("Orchard returned an invalid state atlas")
            if args.domain:
                result = next((item for item in atlas if item.get("id") == args.domain), None)
                if result is None:
                    available = ", ".join(item.get("id", "") for item in atlas)
                    raise RuntimeError(f"Unknown atlas domain {args.domain}. Available: {available}")
            else:
                result = {
                    "domains": [
                        {
                            "id": item.get("id"),
                            "states": len(item.get("states", [])),
                            "transitions": len(item.get("transitions", [])),
                        }
                        for item in atlas
                    ]
                }
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
