"""Small JSON-schema validator and fixture checker for contract v1."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "1.0"
PROTOCOL_VERSION = 1
ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "contracts" / "v1" / "schema.json"
FIXTURES_PATH = ROOT / "contracts" / "v1" / "fixtures"


class SchemaValidationError(ValueError):
    """Raised when a document does not conform to a contract definition."""

    def __init__(self, kind: str, errors: list[str]):
        self.kind = kind
        self.errors = errors
        super().__init__(f"{kind}: " + "; ".join(errors))


def load_schema() -> dict[str, Any]:
    with SCHEMA_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def _resolve_ref(ref: str, schema: dict[str, Any]) -> dict[str, Any]:
    if ref == "#/$defs/node":
        return schema["$defs"]["node"]
    raise ValueError(f"unsupported schema reference: {ref}")


def _type_matches(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "null":
        return value is None
    raise ValueError(f"unsupported schema type: {expected}")


def _validate(value: Any, definition: dict[str, Any], schema: dict[str, Any], path: str, errors: list[str]) -> None:
    if "$ref" in definition:
        _validate(value, _resolve_ref(definition["$ref"], schema), schema, path, errors)
        return

    expected = definition.get("type")
    if expected is not None:
        types = expected if isinstance(expected, list) else [expected]
        if not any(_type_matches(value, item) for item in types):
            errors.append(f"{path}: expected {expected}")
            return

    if "const" in definition and value != definition["const"]:
        errors.append(f"{path}: expected {definition['const']!r}")
    if "enum" in definition and value not in definition["enum"]:
        errors.append(f"{path}: expected one of {definition['enum']!r}")
    if "pattern" in definition and isinstance(value, str) and re.fullmatch(definition["pattern"], value) is None:
        errors.append(f"{path}: does not match {definition['pattern']!r}")
    if "minimum" in definition and isinstance(value, (int, float)) and value < definition["minimum"]:
        errors.append(f"{path}: must be >= {definition['minimum']}")

    if isinstance(value, dict):
        properties = definition.get("properties", {})
        for required in definition.get("required", []):
            if required not in value:
                errors.append(f"{path}: missing required property {required!r}")
        if definition.get("additionalProperties") is False:
            for key in value:
                if key not in properties:
                    errors.append(f"{path}: unexpected property {key!r}")
        for key, child_definition in properties.items():
            if key in value:
                _validate(value[key], child_definition, schema, f"{path}.{key}", errors)

    if isinstance(value, list):
        if "minItems" in definition and len(value) < definition["minItems"]:
            errors.append(f"{path}: requires at least {definition['minItems']} item(s)")
        item_definition = definition.get("items")
        if item_definition:
            for index, item in enumerate(value):
                _validate(item, item_definition, schema, f"{path}[{index}]", errors)


def validate_document(document: Any, kind: str) -> list[str]:
    schema = load_schema()
    try:
        definition = schema["definitions"][kind]
    except KeyError as exc:
        raise ValueError(f"unknown contract definition: {kind}") from exc
    errors: list[str] = []
    _validate(document, definition, schema, "$", errors)
    return errors


def assert_valid(document: Any, kind: str) -> None:
    errors = validate_document(document, kind)
    if errors:
        raise SchemaValidationError(kind, errors)


def check_fixtures() -> tuple[int, int]:
    """Validate every fixture and return (positive_count, negative_count)."""

    positive_count = 0
    negative_count = 0
    for category, should_pass in (("positive", True), ("negative", False)):
        for path in sorted((FIXTURES_PATH / category).glob("*.json")):
            kind = next(
                (
                    candidate
                    for candidate in ("task_submit", "trace_event", "observation", "action", "receipt", "verification")
                    if path.stem == candidate or path.stem.startswith(candidate + "_")
                ),
                None,
            )
            if kind is None:
                raise AssertionError(f"cannot infer fixture kind from {path.name}")
            with path.open(encoding="utf-8") as handle:
                document = json.load(handle)
            errors = validate_document(document, kind)
            if should_pass and errors:
                raise AssertionError(f"positive fixture {path}: {errors}")
            if not should_pass and not errors:
                raise AssertionError(f"negative fixture unexpectedly valid {path}")
            if should_pass:
                positive_count += 1
            else:
                negative_count += 1
    schema = load_schema()
    if schema.get("$id") != "jev-mobile-agent://contracts/v1/schema.json":
        raise AssertionError("contract schema id is not versioned as v1")
    if schema["definitions"]["task_submit"]["properties"]["schema_version"].get("const") != SCHEMA_VERSION:
        raise AssertionError("schema version constant and validator disagree")
    return positive_count, negative_count
