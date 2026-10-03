from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]



    # Stream failures and tool events are executed against real responses in
    # HermesSseClientTest, not inferred from a source-level call signature.






def test_android_chat_ui_and_native_tool_prompt_stay_compact_on_large_font_phone_screens():
    chat_screen = (REPO_ROOT / "android/app/src/main/java/com/mobilefork/hermesagent/ui/chat/ChatScreen.kt").read_text(encoding="utf-8")
    app_shell = (REPO_ROOT / "android/app/src/main/java/com/mobilefork/hermesagent/ui/shell/AppShell.kt").read_text(encoding="utf-8")
    native_tool_client = (REPO_ROOT / "android/app/src/main/java/com/mobilefork/hermesagent/ui/chat/NativeToolCallingChatClient.kt").read_text(encoding="utf-8")

    assert 'placeholder = {' in chat_screen
    assert 'label = { Text(strings.messageHermes' not in chat_screen
    assert '.fillMaxWidth()\n                    .testTag("HermesChatSendButton")' in chat_screen
    assert 'TextOverflow.Ellipsis' in chat_screen
    assert 'modifier = Modifier.size(22.dp)' in app_shell
    assert 'style = MaterialTheme.typography.bodyLarge' in app_shell
    assert 'initialToolSpecsFor(' in native_tool_client
    assert 'val requestToolScope = requestToolScopeFor(userText)' in native_tool_client
    assert 'val contextRecoveryToolSpecs = requestToolScope.scopedToolSpecs()' in native_tool_client
    assert 'if (!isAffirmativeActionRequest(actionText)) return NativeToolRequestScope(emptyList())' in native_tool_client
    assert 'return JSONArray()' in native_tool_client
    assert 'systemMessage(\n                toolSpecs = activeToolSpecs,' in native_tool_client
    assert 'buildFocusedSystemPromptContent(' in native_tool_client
    assert 'if (toolNames.isEmpty()) {' in native_tool_client
    assert 'relevantMemoryContext = relevantMemoryContext' in native_tool_client
    assert 'compactCustomSystemPrompt' in native_tool_client
    assert 'Keep replies brief and direct.' in native_tool_client
    assert 'inferredToolNames(userText: String)' in native_tool_client
    assert '"launch browser"' in native_tool_client
    assert '"browse url"' in native_tool_client
    assert '"browser" in lower' in native_tool_client
    assert 'create_intent_task: start_activity, open_uri, or send_broadcast' in native_tool_client
    assert 'explicitlyRequestedToolNames(userText)' in native_tool_client
    assert 'formatNativeChatError' in native_tool_client
    assert 'The local model ran out of context' in native_tool_client
    assert 'Native chat request failed: ${response.code} $body' not in native_tool_client


def test_chat_streaming_stays_pinned_to_latest_message_bottom_anchor():
    chat_screen = (REPO_ROOT / "android/app/src/main/java/com/mobilefork/hermesagent/ui/chat/ChatScreen.kt").read_text(encoding="utf-8")

    assert "latestMessageFingerprint" in chat_screen
    assert "${message.id}:${message.role}:${message.content.length}:${uiState.messages.size}" in chat_screen
    assert "LaunchedEffect(latestMessageFingerprint, uiState.isSending, uiState.isShowingHistory, chatDisplayMode)" in chat_screen
    assert "listState.scrollToItem(targetIndex)" in chat_screen
    assert "listState.animateScrollToItem(targetIndex)" in chat_screen
    assert 'item(key = "HermesChatBottomAnchor")' in chat_screen
    assert 'testTag("HermesChatBottomAnchor")' in chat_screen
