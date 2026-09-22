"""Small dependency-free validator for the Android observation extension."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "1.0"
ANDROID_SCHEMA_VERSION = "1.0"
PROTOCOL_VERSION = 1
ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "contracts" / "android" / "schema.json"


class SchemaValidationError(ValueError):
    """Raised when an Android extension document is malformed."""

    def __init__(self, kind: str, errors: list[str]):
        self.kind = kind
        self.errors = errors
        super().__init__(f"{kind}: " + "; ".join(errors))


def load_schema() -> dict[str, Any]:
    with SCHEMA_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def _resolve_ref(ref: str, schema: dict[str, Any]) -> dict[str, Any]:
    prefix = "#/$defs/"
    if ref.startswith(prefix):
        return schema["$defs"][ref[len(prefix) :]]
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
    if "maximum" in definition and isinstance(value, (int, float)) and value > definition["maximum"]:
        errors.append(f"{path}: must be <= {definition['maximum']}")
    if "minLength" in definition and isinstance(value, str) and len(value) < definition["minLength"]:
        errors.append(f"{path}: must contain at least {definition['minLength']} character(s)")

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
        definition = schema["$defs"][kind]
    except KeyError as exc:
        raise ValueError(f"unknown Android contract definition: {kind}") from exc
    errors: list[str] = []
    _validate(document, definition, schema, "$", errors)
    return errors


def assert_valid(document: Any, kind: str) -> None:
    errors = validate_document(document, kind)
    if errors:
        raise SchemaValidationError(kind, errors)


def validate_observation_links(observation: dict[str, Any]) -> None:
    """Validate the graph links in the flattened accessibility tree."""

    nodes = observation["nodes"]
    ids = [node["node_id"] for node in nodes]
    if len(ids) != len(set(ids)):
        raise SchemaValidationError("observation", ["$.nodes: node_id values must be unique"])
    node_ids = set(ids)
    if not set(observation["root_node_ids"]).issubset(node_ids):
        raise SchemaValidationError("observation", ["$.root_node_ids: every root must refer to a node"])
    for node in nodes:
        parent_id = node["parent_node_id"]
        if parent_id is not None and parent_id not in node_ids:
            raise SchemaValidationError("observation", [f"$.nodes[{node['node_id']}].parent_node_id: unknown node"])
        if not set(node["child_node_ids"]).issubset(node_ids):
            raise SchemaValidationError("observation", [f"$.nodes[{node['node_id']}].child_node_ids: unknown node"])
    availability = observation["availability"]
    permission = observation["permission"]
    if availability == "AVAILABLE" and (observation["page_state"] != "observed" or not observation["nodes"]):
        raise SchemaValidationError("observation", ["AVAILABLE requires a non-empty observed tree"])
    if availability == "EMPTY_TREE" and observation["page_state"] != "empty":
        raise SchemaValidationError("observation", ["EMPTY_TREE requires page_state empty"])
    if availability == "PERMISSION_UNAVAILABLE" and permission["can_observe"]:
        raise SchemaValidationError("observation", ["PERMISSION_UNAVAILABLE cannot claim can_observe"])
    if availability in {"EMPTY_TREE", "PERMISSION_UNAVAILABLE", "DISCONNECTED"} and observation["unavailable_reason"] is None:
        raise SchemaValidationError("observation", ["unavailable observations require a reason"])


def assert_observation_valid(document: dict[str, Any]) -> None:
    assert_valid(document, "observation")
    validate_observation_links(document)
