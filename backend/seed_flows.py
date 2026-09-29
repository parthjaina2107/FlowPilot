"""
FlowPilot - Multi-App Demo Flow Seeder
Populates SQLite and ChromaDB with pre-compiled flows across multiple Android applications:
  1. Zomato: Order food with custom dish and quantity
  2. YouTube: Search and play video/music
  3. WhatsApp: Send a message to a contact
  4. Spotify: Search and play track
"""

import sys
from pathlib import Path

# Add backend root to sys.path
backend_dir = Path(__file__).resolve().parent
sys.path.insert(0, str(backend_dir))

from models.database import init_db, save_flow, index_trigger_phrases
from models.schemas import FlowGraph, FlowStep

SAMPLE_FLOWS = [
    FlowGraph(
        flow_id="flow-zomato-001",
        flow_name="Order Food on Zomato",
        description="Searches for a dish, adds to cart, and navigates to checkout on Zomato",
        trigger_phrases=[
            "Order butter chicken on Zomato",
            "Get food from Zomato",
            "Order dinner from Zomato",
            "Zomato food order"
        ],
        target_app_package="com.application.zomato",
        parameter_schema={
            "dish_name": {"type": "string", "description": "Dish to order", "default": "butter chicken"},
            "quantity": {"type": "integer", "description": "Quantity", "default": 1}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.application.zomato"}, description="Launch Zomato", wait_after_ms=2500),
            FlowStep(step_index=1, action_type="click", selector={"role": "edittext", "text_contains": "Restaurant name or a dish"}, description="Tap search bar", wait_after_ms=800),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search"}, parameter_slot="dish_name", default_value="butter chicken", description="Type dish name", wait_after_ms=1500),
            FlowStep(step_index=3, action_type="click", selector={"role": "button", "text_contains": "Add"}, description="Tap Add button", wait_after_ms=1000),
            FlowStep(step_index=4, action_type="click", selector={"role": "button", "text_contains": "View Cart"}, description="Proceed to cart", wait_after_ms=1500),
            FlowStep(step_index=5, action_type="click", selector={"role": "button", "text_contains": "Place Order"}, description="Place order", wait_after_ms=1000, is_auth_pause=True)
        ],
        created_at="2026-09-29T10:00:00Z",
        version=1
    ),
    FlowGraph(
        flow_id="flow-youtube-002",
        flow_name="Play Video on YouTube",
        description="Opens YouTube, searches for a song or video title, and starts playback",
        trigger_phrases=[
            "Play music on YouTube",
            "Search for a video on YouTube",
            "Watch a video on YouTube",
            "YouTube play song"
        ],
        target_app_package="com.google.android.youtube",
        parameter_schema={
            "query": {"type": "string", "description": "Search term or song title", "default": "lofi hip hop"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.google.android.youtube"}, description="Open YouTube", wait_after_ms=2000),
            FlowStep(step_index=1, action_type="click", selector={"role": "imageview", "content_description_contains": "Search"}, description="Tap search icon", wait_after_ms=800),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search YouTube"}, parameter_slot="query", default_value="lofi hip hop", description="Type video title", wait_after_ms=1500),
            FlowStep(step_index=3, action_type="click", selector={"role": "viewgroup", "text_contains": "lofi"}, description="Tap first search result", wait_after_ms=2000)
        ],
        created_at="2026-09-29T10:05:00Z",
        version=1
    ),
    FlowGraph(
        flow_id="flow-whatsapp-003",
        flow_name="Send WhatsApp Message",
        description="Opens WhatsApp, finds a contact, types a message, and sends it",
        trigger_phrases=[
            "Send a message on WhatsApp",
            "Text someone on WhatsApp",
            "WhatsApp message",
            "Send WhatsApp to"
        ],
        target_app_package="com.whatsapp",
        parameter_schema={
            "contact_name": {"type": "string", "description": "Recipient name", "default": "Mom"},
            "message": {"type": "string", "description": "Text message content", "default": "On my way home!"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.whatsapp"}, description="Open WhatsApp", wait_after_ms=2000),
            FlowStep(step_index=1, action_type="click", selector={"role": "imageview", "content_description_contains": "Search"}, description="Tap search contact", wait_after_ms=800),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search"}, parameter_slot="contact_name", default_value="Mom", description="Search contact", wait_after_ms=1200),
            FlowStep(step_index=3, action_type="click", selector={"role": "relativelayout", "text_contains": "Mom"}, description="Select contact chat", wait_after_ms=1000),
            FlowStep(step_index=4, action_type="type", selector={"role": "edittext", "text_contains": "Message"}, parameter_slot="message", default_value="On my way home!", description="Type message", wait_after_ms=1000),
            FlowStep(step_index=5, action_type="click", selector={"role": "imageview", "content_description_contains": "Send"}, description="Send message", wait_after_ms=800)
        ],
        created_at="2026-09-29T10:10:00Z",
        version=1
    ),
    FlowGraph(
        flow_id="flow-spotify-004",
        flow_name="Play Track on Spotify",
        description="Opens Spotify, navigates to search, enters an artist or track, and starts playback",
        trigger_phrases=[
            "Play song on Spotify",
            "Put some music on Spotify",
            "Listen to artist on Spotify",
            "Spotify play"
        ],
        target_app_package="com.spotify.music",
        parameter_schema={
            "track_or_artist": {"type": "string", "description": "Track or artist name", "default": "Coldplay"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.spotify.music"}, description="Open Spotify", wait_after_ms=2500),
            FlowStep(step_index=1, action_type="click", selector={"role": "button", "content_description_contains": "Search"}, description="Tap search tab", wait_after_ms=1000),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "What do you want to listen to?"}, parameter_slot="track_or_artist", default_value="Coldplay", description="Type artist or track", wait_after_ms=1500),
            FlowStep(step_index=3, action_type="click", selector={"role": "viewgroup", "text_contains": "Coldplay"}, description="Tap top search result", wait_after_ms=1500)
        ],
        created_at="2026-09-29T10:15:00Z",
        version=1
    )
]

def seed_database():
    init_db()
    for flow in SAMPLE_FLOWS:
        save_flow(
            flow_id=flow.flow_id,
            flow_name=flow.flow_name,
            description=flow.description,
            target_app_package=flow.target_app_package,
            flow_json=flow.model_dump_json(),
            created_at=flow.created_at
        )
        index_trigger_phrases(
            flow_id=flow.flow_id,
            flow_name=flow.flow_name,
            phrases=flow.trigger_phrases
        )
        print(f"[OK] Seeded flow: {flow.flow_name} ({len(flow.steps)} steps)")

if __name__ == "__main__":
    seed_database()
    print(f"\n[DONE] Successfully seeded {len(SAMPLE_FLOWS)} demo flows!")
