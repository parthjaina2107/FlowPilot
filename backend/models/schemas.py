"""
Pydantic models (schemas) shared across the FlowPilot backend.

These mirror the Kotlin data classes on the Android side.
"""

from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field


# ---------------------------------------------
# LEARN stage - raw recording models
# ---------------------------------------------


class UIAction(BaseModel):
    """A single recorded user action on screen."""

    timestamp: int = Field(..., description="Unix timestamp in ms")
    action_type: str = Field(
        ..., description='One of: "click", "type", "scroll", "long_press"'
    )
    package_name: str = Field(..., description='e.g. "com.zomato.order"')
    activity_name: str = Field(default="", description='e.g. "SearchActivity"')
    element_id: Optional[str] = Field(
        default=None, description="Android resource-id (may be null)"
    )
    element_class: str = Field(
        default="", description='e.g. "android.widget.Button"'
    )
    element_text: Optional[str] = Field(
        default=None, description="Visible text on the element"
    )
    content_description: Optional[str] = Field(
        default=None, description="Accessibility content description"
    )
    bounds: str = Field(
        default="[0,0][0,0]", description='"[left,top][right,bottom]"'
    )
    typed_text: Optional[str] = Field(
        default=None, description='Text entered (for "type" actions)'
    )
    scroll_direction: Optional[str] = Field(
        default=None, description='"up", "down", "left", "right"'
    )


class RecordingTrace(BaseModel):
    """Raw recording from one user demonstration."""

    trace_id: str = Field(..., description="Unique trace identifier")
    flow_name: str = Field(
        ..., description='User-given name, e.g. "Order food from Zomato"'
    )
    trigger_phrase: str = Field(
        ..., description="The voice command used during the demo"
    )
    target_app_package: str = Field(
        ..., description="Package of the app being automated"
    )
    actions: list[UIAction] = Field(
        ..., description="Ordered list of captured actions"
    )
    recorded_at: str = Field(..., description="ISO 8601 timestamp")


# ---------------------------------------------
# GENERALISE stage - compiled flow models
# ---------------------------------------------


class FlowStep(BaseModel):
    """One step in a generalised, reusable flow."""

    step_index: int
    action_type: str = Field(
        ...,
        description='One of: "click", "type", "scroll", "long_press", "wait", "open_app"',
    )
    selector: dict[str, str] = Field(
        ...,
        description=(
            "Semantic selector to find the UI element. Keys can include: "
            '"role", "text_contains", "text_equals", '
            '"content_description_contains", "class_name", "resource_id_contains"'
        ),
    )
    parameter_slot: Optional[str] = Field(
        default=None,
        description='If this step uses a variable, e.g. "item_name", "quantity"',
    )
    default_value: Optional[str] = Field(
        default=None, description="Default value from the original demo"
    )
    description: str = Field(
        ..., description='Human-readable, e.g. "Tap the search bar"'
    )
    wait_after_ms: int = Field(
        default=1000, description="Delay after this step (ms)"
    )
    is_auth_pause: bool = Field(
        default=False,
        description="True if this step should pause for user auth (payment, OTP)",
    )


class FlowGraph(BaseModel):
    """A generalised, reusable, parameterised flow."""

    flow_id: str = Field(..., description="UUID")
    flow_name: str
    description: str = Field(
        ...,
        description=(
            "What this flow does, e.g. "
            '"Orders food from Zomato with customisable item and quantity"'
        ),
    )
    trigger_phrases: list[str] = Field(
        ...,
        description="Multiple ways to trigger this flow via voice",
    )
    target_app_package: str
    parameter_schema: dict[str, dict[str, Any]] = Field(
        default_factory=dict,
        description=(
            'e.g. {"item_name": {"type": "string", '
            '"description": "Food item to order", "default": "paneer"}}'
        ),
    )
    steps: list[FlowStep]
    created_at: str = Field(..., description="ISO 8601 timestamp")
    version: int = Field(default=1)


# ---------------------------------------------
# MATCH stage - request / response models
# ---------------------------------------------


class MatchRequest(BaseModel):
    """Voice command text to match against stored flows."""

    command: str = Field(
        ..., description='e.g. "Order 2 naans from Zomato"'
    )


class MatchResult(BaseModel):
    """Result of matching a voice command to a flow."""

    matched: bool
    flow_id: Optional[str] = None
    flow_name: Optional[str] = None
    confidence: Optional[float] = None
    parameters: Optional[dict[str, Any]] = None
    flow_graph: Optional[FlowGraph] = None
    transcribed_text: Optional[str] = None
    suggestion: Optional[str] = None
