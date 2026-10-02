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
            "Order a Margherita pizza from Domino's on Zomato",
            "Get me a margherita from dominos",
            "I want to order margherita pizza on zomato",
            "Order a Farmhouse pizza from Domino's on Zomato",
            "Order two Margherita pizzas from Domino's",
            "Order a Margherita from Domino's, deliver to work",
            "Order two naans from Zomato",
            "Get me two naans from Zomato",
            "I want two naans delivered from Zomato",
            "Order two naans",
            "Order butter chicken on Zomato",
            "Order food on Zomato",
            "Get food from Zomato",
            "Order dinner from Zomato",
            "Zomato food order",
            "Order pizza on Zomato",
            "Order biryani on Zomato",
            "Order butter chicken to Home on Zomato",
            "Deliver food to Work from Zomato"
        ],
        target_app_package="com.application.zomato",
        parameter_schema={
            "dish_name": {"type": "string", "description": "Dish to order", "default": "Margherita pizza"},
            "restaurant": {"type": "string", "description": "Restaurant name", "default": "Domino's"},
            "quantity": {"type": "integer", "description": "Quantity", "default": 1},
            "address": {"type": "string", "description": "Delivery address or label (e.g. Home, Work)", "default": "Home"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.application.zomato"}, description="Launch Zomato", wait_after_ms=2500),
            FlowStep(step_index=1, action_type="click", selector={"role": "edittext", "text_contains": "Restaurant name or a dish"}, description="Tap search bar", wait_after_ms=1000),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search"}, parameter_slot="dish_name", default_value="Margherita pizza", description="Type dish name", wait_after_ms=1500),
            FlowStep(step_index=3, action_type="click", selector={"text_contains": "Margherita"}, parameter_slot="dish_name", default_value="Margherita", description="Select dish from search results", wait_after_ms=2000),
            FlowStep(step_index=4, action_type="click", selector={"role": "button", "text_contains": "Add"}, description="Tap Add button", wait_after_ms=1200),
            FlowStep(step_index=5, action_type="click", selector={"role": "button", "text_contains": "View Cart"}, description="Proceed to cart", wait_after_ms=1500),
            FlowStep(step_index=6, action_type="click", selector={"role": "view", "text_contains": "Deliver to Home"}, parameter_slot="address", default_value="Home", description="Select delivery address", wait_after_ms=1200),
            FlowStep(step_index=7, action_type="click", selector={"role": "button", "text_contains": "Place Order"}, description="Place order", wait_after_ms=1000, is_auth_pause=True)
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
            "YouTube play song",
            "Put on music on YouTube",
            "Play lofi hip hop on YouTube",
            "Play jazz on YouTube",
            "Play video on YouTube",
            "Play song on YouTube"
        ],
        target_app_package="com.google.android.youtube",
        parameter_schema={
            "query": {"type": "string", "description": "Search term or song title", "default": "lofi hip hop"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.google.android.youtube"}, description="Open YouTube", wait_after_ms=2500),
            FlowStep(step_index=1, action_type="click", selector={"role": "imageview", "content_description_contains": "Search"}, description="Tap search icon", wait_after_ms=1500),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search YouTube"}, parameter_slot="query", default_value="lofi hip hop", description="Type video title", wait_after_ms=2500),
            FlowStep(step_index=3, action_type="click", selector={"role": "viewgroup", "content_description_contains": "lofi"}, parameter_slot="query", default_value="lofi", description="Tap video to play", wait_after_ms=3000)
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
            "Send WhatsApp to",
            "Send message to Mom on WhatsApp",
            "Send hi to Mom on WhatsApp",
            "Message Mom on WhatsApp",
            "Send Dad a message saying I'm running late",
            "Send Dad a message saying",
            "Send a message saying",
            "Send message to Dad",
            "Message Dad on WhatsApp",
            "Text Dad on WhatsApp"
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
            FlowStep(step_index=3, action_type="click", selector={"role": "viewgroup", "text_contains": "Mom"}, parameter_slot="contact_name", default_value="Mom", description="Select contact chat", wait_after_ms=1000),
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
            "Spotify play",
            "Play music on Spotify",
            "Play queen on Spotify"
        ],
        target_app_package="com.spotify.music",
        parameter_schema={
            "track_or_artist": {"type": "string", "description": "Track or artist name", "default": "Coldplay"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "com.spotify.music"}, description="Open Spotify", wait_after_ms=2500),
            FlowStep(step_index=1, action_type="click", selector={"role": "button", "content_description_contains": "Search"}, description="Tap search tab", wait_after_ms=1000),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search"}, parameter_slot="track_or_artist", default_value="Coldplay", description="Type artist or track", wait_after_ms=1500),
            FlowStep(step_index=3, action_type="click", selector={"role": "viewgroup", "text_contains": "Coldplay"}, parameter_slot="track_or_artist", default_value="Coldplay", description="Tap top search result", wait_after_ms=1500)
        ],
        created_at="2026-09-29T10:15:00Z",
        version=1
    ),
    FlowGraph(
        flow_id="flow-amazon-005",
        flow_name="Buy Product on Amazon",
        description="Searches for an item on Amazon, selects the first result, adds to cart, and proceeds to checkout",
        trigger_phrases=[
            "Buy protein powder on Amazon",
            "Order item on Amazon",
            "Search and buy on Amazon",
            "Amazon buy product",
            "Purchase something on Amazon",
            "Search on Amazon",
            "Buy on Amazon",
            "Search macbook on Amazon",
            "Buy headphones on Amazon"
        ],
        target_app_package="in.amazon.mShop.android.shopping",
        parameter_schema={
            "item_name": {"type": "string", "description": "Product or item to search and buy", "default": "protein powder"}
        },
        steps=[
            FlowStep(step_index=0, action_type="open_app", selector={"role": "app", "package": "in.amazon.mShop.android.shopping"}, description="Launch Amazon", wait_after_ms=3000),
            FlowStep(step_index=1, action_type="click", selector={"role": "edittext", "text_contains": "Search"}, description="Tap search bar", wait_after_ms=1000),
            FlowStep(step_index=2, action_type="type", selector={"role": "edittext", "text_contains": "Search"}, parameter_slot="item_name", default_value="protein powder", description="Type item name", wait_after_ms=1500),
            FlowStep(step_index=3, action_type="click", selector={"role": "viewgroup", "text_contains": "protein powder"}, parameter_slot="item_name", default_value="protein powder", description="Tap search suggestion", wait_after_ms=2500),
            FlowStep(step_index=4, action_type="click", selector={"role": "viewgroup", "text_contains": "Results"}, description="Select first search result", wait_after_ms=2000),
            FlowStep(step_index=5, action_type="click", selector={"role": "button", "text_contains": "Add to Cart"}, description="Tap Add to Cart", wait_after_ms=1500),
            FlowStep(step_index=6, action_type="click", selector={"role": "button", "text_contains": "checkout"}, description="Proceed to checkout", wait_after_ms=1200, is_auth_pause=True)
        ],
        created_at="2026-09-30T10:00:00Z",
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
