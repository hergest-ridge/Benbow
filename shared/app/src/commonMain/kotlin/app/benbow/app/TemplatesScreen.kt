package app.benbow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.benbow.app.resources.Res
import app.benbow.app.resources.cancel
import app.benbow.app.resources.menu
import app.benbow.app.resources.save
import app.benbow.app.resources.template_copy_name
import app.benbow.app.resources.template_duplicate
import app.benbow.app.resources.template_duplicate_hint
import app.benbow.app.resources.template_name
import app.benbow.app.resources.template_new
import app.benbow.app.resources.template_rename
import app.benbow.app.resources.template_title
import app.benbow.app.resources.template_trash
import app.benbow.app.resources.template_write
import app.benbow.app.resources.templates
import app.benbow.app.resources.templates_hint
import app.benbow.app.resources.templates_manage
import app.benbow.app.resources.templates_mine
import app.benbow.app.resources.templates_none
import app.benbow.app.resources.templates_standard
import app.benbow.client.JournalRepository
import app.benbow.client.OwnTemplate
import app.benbow.client.Template
import app.benbow.client.Templates
import app.benbow.client.ownTemplates
import app.benbow.client.renameTemplate
import app.benbow.client.templateBody
import app.benbow.ui.Hinted
import org.jetbrains.compose.resources.stringResource

/*
 * «Ещё → Шаблоны» (ADR-061): one's own templates, each an entry of its own opened in the real editor, then the
 * standard ones from the app, which can't be changed or deleted: «Дублировать» makes one's own copy to change.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplatesScreen(services: AppServices, nav: Navigator) {
    val own by remember { services.repo.ownTemplates() }.collectAsStateWithLifecycle(emptyList())
    var naming by remember { mutableStateOf<TemplateNaming?>(null) }
    val write = { id: String -> nav.open(Route.Editor(null, services.today(), opened = services.now(), template = id)) }
    val open = { id: String -> nav.open(Route.Editor(id, services.today())) }
    val copyName = stringResource(Res.string.template_copy_name, "%s")
    val newName = stringResource(Res.string.template_title)
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { BackButton(nav::back) },
            title = { Text(stringResource(Res.string.templates)) },
            actions = {
                val label = stringResource(Res.string.template_new)
                Hinted(label) {
                    IconButton({ naming = TemplateNaming.New(newName) }, Modifier.hand()) { Icon(Icons.Add, label) }
                }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hint") {
                Text(
                    stringResource(Res.string.templates_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item(key = "mine") { TemplatesHeader(stringResource(Res.string.templates_mine)) }
            if (own.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(Res.string.templates_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            items(own, key = { it.id }) { template ->
                TemplateItem(template.name, template.preview, onClick = { open(template.id) }) { close ->
                    DropdownMenuItem(text = { Text(stringResource(Res.string.template_write)) }, onClick = {
                        close()
                        write(template.id)
                    }, modifier = Modifier.hand())
                    DropdownMenuItem(text = { Text(stringResource(Res.string.template_rename)) }, onClick = {
                        close()
                        naming = TemplateNaming.Rename(template)
                    }, modifier = Modifier.hand())
                    DropdownMenuItem(text = { Text(stringResource(Res.string.template_duplicate)) }, onClick = {
                        close()
                        naming = TemplateNaming.CopyOwn(template, copyName.replace("%s", template.name))
                    }, modifier = Modifier.hand())
                    DropdownMenuItem(text = { Text(stringResource(Res.string.template_trash)) }, onClick = {
                        close()
                        services.trashTemplate(template.id, template.name)
                    }, modifier = Modifier.hand())
                }
            }
            item(key = "standard") { TemplatesHeader(stringResource(Res.string.templates_standard)) }
            items(Templates.builtIn, key = { "s:" + it.id }) { template ->
                TemplateItem(template.name, standardPreview(template), onClick = { write(template.id) }) { close ->
                    DropdownMenuItem(text = { Text(stringResource(Res.string.template_write)) }, onClick = {
                        close()
                        write(template.id)
                    }, modifier = Modifier.hand())
                    DropdownMenuItem(text = { Text(stringResource(Res.string.template_duplicate_hint)) }, onClick = {
                        close()
                        naming = TemplateNaming.CopyStandard(template, copyName.replace("%s", template.name))
                    }, modifier = Modifier.hand())
                }
            }
        }
    }
    naming?.let { asked ->
        TemplateNameDialog(asked.name, onDismiss = { naming = null }) { name ->
            naming = null
            when (asked) {
                is TemplateNaming.New -> open(services.saveAsTemplate(name, JournalRepository.emptyBody(), open = true))

                is TemplateNaming.Rename -> services.repo.renameTemplate(asked.template.id, name)

                is TemplateNaming.CopyOwn -> services.repo.templateBody(asked.template.id)?.let { body ->
                    open(services.saveAsTemplate(name, body, from = asked.template.id, open = true))
                }

                is TemplateNaming.CopyStandard -> open(
                    services.saveAsTemplate(name, Templates.render(asked.template, Templates.KEEP), open = true),
                )
            }
        }
    }
}

/** What a name is asked for, with the name the field starts with. */
private sealed interface TemplateNaming {
    val name: String

    data class New(override val name: String) : TemplateNaming

    data class Rename(val template: OwnTemplate) : TemplateNaming {
        override val name get() = template.name
    }

    data class CopyOwn(val template: OwnTemplate, override val name: String) : TemplateNaming

    data class CopyStandard(val template: Template, override val name: String) : TemplateNaming
}

/** A standard template's headings and words on a line, its placeholders as they read in the template. */
private fun standardPreview(template: Template): String = template.blocks.map { it.text }.filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun TemplatesHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A template's row: its name and what it holds; a tap does [onClick], ⋮ opens its [menu]. */
@Composable
private fun TemplateItem(name: String, preview: String, onClick: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().hand().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Template, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton({ open = true }, Modifier.hand()) { Icon(Icons.More, stringResource(Res.string.menu)) }
            DropdownMenu(open, { open = false }) { menu { open = false } }
        }
    }
}

/** An open template's title (ADR-061): its name, «Шаблон» under it; a tap renames it. */
@Composable
internal fun TemplateTitle(name: String, onRename: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).hand().clickable(onClick = onRename)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(Res.string.template_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The templates to start a new entry from, one's own then the standard ones, as a menu (a long press on the quick bar's
 * ✎, ADR-061); «Шаблоны…» opens them all.
 */
@Composable
internal fun TemplateMenu(services: AppServices, expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit, onManage: () -> Unit) {
    val own by remember { services.repo.ownTemplates() }.collectAsStateWithLifecycle(emptyList())
    DropdownMenu(expanded, onDismiss) {
        own.forEach { template ->
            DropdownMenuItem(
                text = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Template, null) },
                onClick = {
                    onDismiss()
                    onPick(template.id)
                },
                modifier = Modifier.hand(),
            )
        }
        if (own.isNotEmpty()) HorizontalDivider()
        Templates.builtIn.forEach { template ->
            DropdownMenuItem(text = { Text(template.name) }, onClick = {
                onDismiss()
                onPick(template.id)
            }, modifier = Modifier.hand())
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(Res.string.templates_manage)) }, onClick = {
            onDismiss()
            onManage()
        }, modifier = Modifier.hand())
    }
}

/** A right click (a mouse's other button) does [action]; a finger never presses it. */
internal fun Modifier.onSecondaryPress(enabled: Boolean = true, action: () -> Unit): Modifier = if (!enabled) {
    this
} else {
    pointerInput(action) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    action()
                }
            }
        }
    }
}

/** Asks a template's name, [initial] chosen to be typed over. */
@Composable
internal fun TemplateNameDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val done = { name.text.trim().takeIf { it.isNotEmpty() }?.let(onDone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.template_name)) },
        text = {
            OutlinedTextField(
                name,
                { name = it },
                singleLine = true,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton({ done() }, Modifier.hand(), enabled = name.text.isNotBlank()) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.hand()) { Text(stringResource(Res.string.cancel)) } },
    )
}
