import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.orchard.frontend.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Orchard") {
        App()
    }
}
