package ee.carlrobert.codegpt.ui.textarea

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.ReferencedFile
import ee.carlrobert.codegpt.actions.IncludeFilesInContextNotifier
import ee.carlrobert.codegpt.ui.IconActionButton
import ee.carlrobert.codegpt.util.file.FileUtil
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Paths
import javax.swing.*
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext
import javax.swing.text.StyledDocument

class SmartTextPane(
    project: Project,
    private val onSubmit: (String) -> Unit,
    private val onStop: () -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val fileSearchService = FileSearchService(project)
    private val suggestionsPopupManager = SuggestionsPopupManager(project) {
        handleFileSelection(it)
    }
    private val textPane = PlaceholderTextPane { handleSubmit() }.apply {
        addKeyListener(keyAdapter())
    }
    private val submitButton = IconActionButton(
        object : AnAction("Send Message", "Send message", Icons.Send) {
            override fun actionPerformed(e: AnActionEvent) {
                handleSubmit()
            }
        }
    )
    private val stopButton = IconActionButton(
        object : AnAction("Stop", "Stop current inference", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                onStop()
            }
        }
    ).apply { isEnabled = false }

    val text: String
        get() = textPane.text

    init {
        isOpaque = false
        add(textPane, BorderLayout.CENTER)
        add(createIconsPanel(), BorderLayout.EAST)
    }

    fun setSubmitEnabled(enabled: Boolean) {
        submitButton.isEnabled = enabled
        stopButton.isEnabled = !enabled
    }

    override fun requestFocus() {
        textPane.requestFocus()
        textPane.requestFocusInWindow()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        super.paintComponent(g)
        g2.dispose()
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBUI.CurrentTheme.ActionButton.focusedBorder()
        if (textPane.isFocusOwner) {
            g2.stroke = BasicStroke(1.5F)
        }
        g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
        g2.dispose()
    }

    override fun getInsets(): Insets = JBUI.insets(6, 12, 6, 6)

    override fun dispose() {
        fileSearchService.dispose()
    }

    private fun updateSuggestions() {
        CoroutineScope(Dispatchers.Default).launch {
            val lastAtIndex = textPane.text.lastIndexOf('@')
            if (lastAtIndex != -1) {
                val searchText = textPane.text.substring(lastAtIndex + 1)
                if (searchText.isNotEmpty()) {
                    val filePaths = fileSearchService.searchFiles(searchText)
                    suggestionsPopupManager.updateSuggestions(filePaths)
                }
            } else {
                suggestionsPopupManager.hidePopup()
            }
        }
    }

    private fun createIconsPanel(): JPanel = JPanel(GridBagLayout()).apply {
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.VERTICAL
            anchor = GridBagConstraints.EAST
            insets = JBUI.insets(0, 4, 0, 4)
        }

        add(submitButton, gbc)
        add(stopButton, gbc)
    }

    private fun handleSubmit() {
        val text = textPane.text.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            textPane.text = ""
        }
    }

    private fun handleFileSelection(filePath: String) {
        val selectedFile = service<VirtualFileManager>().findFileByNioPath(Paths.get(filePath))
        selectedFile?.let { file ->
            textPane.highlightText(file.name)
            fileSearchService.addFileToContext(file)
        }
        suggestionsPopupManager.hidePopup()
    }

    private fun PlaceholderTextPane.keyAdapter() =
        object : KeyAdapter() {
            private val defaultStyle =
                StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)

            override fun keyReleased(e: KeyEvent) {
                if (!text.contains('@')) {
                    suggestionsPopupManager.hidePopup()
                    return
                }

                when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                        suggestionsPopupManager.requestFocus()
                        suggestionsPopupManager.selectNext()
                        e.consume()
                    }

                    else -> {
                        if (suggestionsPopupManager.isPopupVisible()) {
                            updateSuggestions()
                        }
                    }
                }
            }

            override fun keyTyped(e: KeyEvent) {
                val popupVisible = suggestionsPopupManager.isPopupVisible()
                if (e.keyChar == '@' && !popupVisible) {
                    suggestionsPopupManager.showPopup(this@keyAdapter)
                    return
                } else if (e.keyChar == '\t') {
                    suggestionsPopupManager.requestFocus()
                    suggestionsPopupManager.selectNext()
                    e.consume()
                    return
                } else if (popupVisible) {
                    updateSuggestions()
                }

                val doc = document as StyledDocument
                if (caretPosition >= 0) {
                    doc.setCharacterAttributes(caretPosition, 1, defaultStyle, true)
                }
            }
        }
}

class FileSearchService(private val project: Project) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun searchFiles(searchText: String): List<String> = runBlocking {
        withContext(scope.coroutineContext) {
            FileUtil.searchProjectFiles(project, searchText).map { it.path }
        }
    }

    fun addFileToContext(file: VirtualFile) {
        val filesIncluded = ArrayList<ReferencedFile>(
            project.getUserData(CodeGPTKeys.SELECTED_FILES) ?: mutableListOf()
        )
        filesIncluded.add(ReferencedFile(File(file.path)))
        project.putUserData(CodeGPTKeys.SELECTED_FILES, filesIncluded)
        project.messageBus
            .syncPublisher(IncludeFilesInContextNotifier.FILES_INCLUDED_IN_CONTEXT_TOPIC)
            .filesIncluded(filesIncluded)
    }

    fun dispose() {
        scope.cancel()
    }
}

class PlaceholderTextPane(
    private val onSubmit: (String) -> Unit
) : JTextPane() {

    init {
        isOpaque = false
        background = JBColor.namedColor("Editor.SearchField.background")
        document = DefaultStyledDocument()
        border = JBUI.Borders.empty(8, 4)
        isFocusable = true
        font = if (Registry.`is`("ide.find.use.editor.font", false)) {
            EditorUtil.getEditorFont()
        } else {
            UIManager.getFont("TextField.font")
        }
        inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "text-submit")
        actionMap.put("text-submit", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                onSubmit(text)
            }
        })
    }

    fun highlightText(text: String) {
        val lastIndex = this.text.lastIndexOf('@')
        if (lastIndex != -1) {
            val styleContext = StyleContext.getDefaultStyleContext()
            val fileNameStyle = styleContext.addStyle("smart-highlighter", null)
            val fontFamily = service<EditorColorsManager>().globalScheme
                .getFont(EditorFontType.PLAIN)
                .deriveFont(JBFont.label().size.toFloat())
                .family

            StyleConstants.setFontFamily(fileNameStyle, fontFamily)
            StyleConstants.setForeground(
                fileNameStyle,
                JBUI.CurrentTheme.GotItTooltip.codeForeground(true)
            )
            StyleConstants.setBackground(
                fileNameStyle,
                JBUI.CurrentTheme.GotItTooltip.codeBackground(true)
            )

            document.remove(lastIndex + 1, document.length - (lastIndex + 1))
            document.insertString(lastIndex + 1, text, fileNameStyle)
            styledDocument.setCharacterAttributes(
                lastIndex,
                text.length,
                fileNameStyle,
                true
            )
            document.insertString(
                document.length,
                " ",
                styleContext.getStyle(StyleContext.DEFAULT_STYLE)
            )
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (document.length == 0) {
            g2d.color = JBColor.GRAY
            g2d.font = if (Registry.`is`("ide.find.use.editor.font", false)) {
                EditorUtil.getEditorFont()
            } else {
                UIManager.getFont("TextField.font")
            }
            // Draw placeholder
            g2d.drawString(
                CodeGPTBundle.get("toolwindow.chat.textArea.emptyText"),
                insets.left,
                g2d.fontMetrics.maxAscent + insets.top
            )
        }
    }
}