---
name: mood-tracker-lite
description: A lightweight mood tracking skill that stores and visualizes your daily emotional state. Log moods, view history, and see trend analysis with an interactive dashboard.
triggers: ["/mood", "心情记录", "情绪追踪"]
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/built-in/mood-tracker
---

# Mood Tracker Lite

A simple yet powerful mood tracking tool that helps you monitor your daily emotional well-being.

## Instructions

Call the `run_js` tool with `index.html` as the script name and a JSON payload for `data` with the following fields:

### Actions

#### 1. Log Mood
When a user wants to log their mood, call `run_js` with:
- **action**: "log_mood"
- **score**: Number (1-10). The user's current mood score.
- **comment**: String (Optional). A brief note about how they're feeling.
- **date**: String. Use "today", "yesterday", or "YYYY-MM-DD" format.

#### 2. Get Mood for a Specific Date
- **action**: "get_mood"
- **date**: String. The date to query.

#### 3. Get History / Show Dashboard
- **action**: "get_history"
- **days**: Number (Optional, default 7). Number of days to retrieve.
- **show_dashboard**: Boolean (Optional). Set to true to return an interactive webview dashboard.

#### 4. Analyze Mood Trends
- **action**: "analyze_trends"
- **days**: Number (Optional, default 30). Analysis period in days.

## Sample Commands Users Might Say

- "I'm feeling like a 7 today, pretty good after the meeting"
- "Log my mood as 9 — just finished a great workout!"
- "How has my mood been this past week?"
- "Show me my mood dashboard"
- "Am I generally feeling better or worse lately?"
- "What was my mood on March 5th?"

## Rules

- All data is stored locally on the device (localStorage).
- Scores must be between 1 and 10.
- If no date is specified, default to "today".
- When showing the dashboard, also return a webview URL for interactive visualization.
