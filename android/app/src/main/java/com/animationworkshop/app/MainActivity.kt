package com.animationworkshop.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.content.ContentUris
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.animationworkshop.app.ui.theme.AnimationWorkshopTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val Sky = Color(0xFFE9F7F4)
private val Ink = Color(0xFF23413C)
private val Teal = Color(0xFF31A996)
private val Coral = Color(0xFFFF5B59)
private val Cream = Color(0xFFFFFDF5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnimationWorkshopTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
                    StopMotionApp()
                }
            }
        }
    }
}

private data class ProjectSnapshot(val frames: List<AnimationFrame>, val latestBitmap: Bitmap?)

@Composable
private fun StopMotionApp() {
    val context = LocalContext.current
    val projectRepository = remember { ProjectRepository(context) }
    var projects by remember { mutableStateOf<List<ProjectGalleryItem>>(emptyList()) }
    var allFolders by remember { mutableStateOf<List<AnimationFolder>>(emptyList()) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var activeProjectId by remember { mutableStateOf<String?>(null) }
    var projectsReady by remember { mutableStateOf(false) }
    var projectOperationBusy by remember { mutableStateOf(false) }
    var projectMessage by remember { mutableStateOf<String?>(null) }
    val activeProject = projects.firstOrNull { it.project.id == activeProjectId }?.project
    val frameStore = remember(projectRepository, activeProject?.id) { activeProject?.let(projectRepository::frameStore) }
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var timelapseConfig by remember { mutableStateOf<TimelapseConfig?>(null) }
    var modesOpen by remember { mutableStateOf(false) }
    var activeMode by remember { mutableStateOf(ModeDestination.CAMERA) }
    var previewMode by remember { mutableStateOf(ModeDestination.PREVIEW) }
    var exportRequest by remember { mutableStateOf(0) }
    var timelapseActive by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<ModeDestination?>(null) }
    var showTimelapseNavigationConfirm by remember { mutableStateOf(false) }
    var reviewIndex by remember { mutableStateOf(0) }
    var playbackFps by remember { mutableStateOf(8) }
    var confirmFrameDeletion by remember { mutableStateOf(true) }
    var timelineZoomLevel by remember { mutableStateOf(1) }
    var refresh by remember { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf(ProjectSnapshot(emptyList(), null)) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var modeNotice by remember { mutableStateOf<String?>(null) }
    var pendingShareProjectId by remember { mutableStateOf<String?>(null) }
    var autoAdvanceSplash by remember { mutableStateOf(true) }
    var galleryView by remember {
        mutableStateOf(runCatching {
            ProjectGalleryView.valueOf(context.getSharedPreferences("animation_workshop", Context.MODE_PRIVATE)
                .getString("project_gallery_view", ProjectGalleryView.LARGE.name) ?: ProjectGalleryView.LARGE.name)
        }.getOrDefault(ProjectGalleryView.LARGE))
    }
    val persistenceScope = rememberCoroutineScope()
    val activity = context as? ComponentActivity
    val immersive = screen == AppScreen.CAMERA || screen == AppScreen.PREVIEW || screen == AppScreen.TIMELAPSE_SETUP || screen == AppScreen.TIMELAPSE

    BackHandler(enabled = screen == AppScreen.GALLERY || screen == AppScreen.PREVIEW || screen == AppScreen.REVIEW || screen == AppScreen.TIMELAPSE_SETUP || screen == AppScreen.EFFECTS) {
        if (screen == AppScreen.PREVIEW || screen == AppScreen.TIMELAPSE_SETUP || screen == AppScreen.REVIEW) activeMode = ModeDestination.CAMERA
        if (screen == AppScreen.GALLERY) autoAdvanceSplash = false
        screen = when (screen) {
            AppScreen.GALLERY -> AppScreen.HOME
            AppScreen.PREVIEW -> AppScreen.CAMERA
            AppScreen.REVIEW -> AppScreen.HOME
            AppScreen.TIMELAPSE_SETUP -> AppScreen.CAMERA
            AppScreen.EFFECTS -> AppScreen.GALLERY
            else -> screen
        }
    }

    DisposableEffect(activity, immersive) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller?.hide(WindowInsetsCompat.Type.navigationBars())
        else controller?.show(WindowInsetsCompat.Type.navigationBars())
        onDispose {
            if (!immersive) controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
        if (granted) screen = AppScreen.CAMERA
    }

    suspend fun loadGallery(selectId: String? = null) {
        val all = withContext(Dispatchers.IO) { projectRepository.projectsInFolder(currentFolderId) }
        val items = withContext(Dispatchers.IO) {
            all.map { project ->
                val store = projectRepository.frameStore(project)
                val frames = store.frames()
                ProjectGalleryItem(project, frames.size, frames.lastOrNull()?.file, store.playbackFps(), frames)
            }
        }
        projects = items
        allFolders = withContext(Dispatchers.IO) { projectRepository.folders() }
        val preferredId = selectId ?: activeProjectId ?: projectRepository.activeProjectId()
        if (items.none { it.project.id == preferredId }) activeProjectId = items.firstOrNull()?.project?.id
        else activeProjectId = preferredId
        projectsReady = true
    }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { projectRepository.initialize() }
            loadGallery(projectRepository.activeProjectId())
            activeMode = ModeDestination.CAMERA
        } catch (failure: Exception) {
            projectsReady = true
            projectMessage = failure.message?.let { "Your movies could not be opened: $it" }
                ?: "Your movies could not be opened. Please try again."
        }
    }

    fun runProjectOperation(operation: suspend () -> Unit) {
        persistenceScope.launch {
            projectOperationBusy = true
            projectMessage = null
            val result = runCatching { operation() }
            projectOperationBusy = false
            if (result.isFailure) projectMessage = result.exceptionOrNull()?.message ?: "That project change could not be saved."
        }
    }

    fun openProject(project: AnimationProject) {
        runProjectOperation {
            withContext(Dispatchers.IO) { projectRepository.setActiveProjectId(project.id) }
            activeProjectId = project.id
            loadGallery(project.id)
            playbackFps = withContext(Dispatchers.IO) { projectRepository.frameStore(project).playbackFps() }
            refresh++
            activeMode = ModeDestination.CAMERA
            screen = if (hasCameraPermission) AppScreen.CAMERA else AppScreen.CAMERA
        }
    }

    fun shareLatestExportedMovie() {
        persistenceScope.launch {
            val movieUri = withContext(Dispatchers.IO) { findLatestExportedMovieUri(context) }
            if (movieUri == null) {
                modeNotice = "No exported movie was found. Open Preview and choose Export Movie first. / 找不到已匯出的影片。請先開啟預覽並選擇「匯出影片」。"
                return@launch
            }
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, movieUri)
                    clipData = ClipData.newUri(context.contentResolver, "Animation Workshop movie", movieUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share Movie / 分享電影")
                if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }.onFailure {
                modeNotice = "The movie could not be shared. Please try exporting it again. / 無法分享影片，請重新匯出後再試。"
            }
        }
    }

    fun shareMovieUri(movieUri: Uri) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, movieUri)
                clipData = ClipData.newUri(context.contentResolver, "Little Movie Maker movie", movieUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share Movie / 分享電影")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.onFailure { modeNotice = "The movie could not be shared. Please try again. / 無法分享影片，請再試一次。" }
    }

    fun navigateToMode(destination: ModeDestination) {
        modesOpen = false
        if (timelapseActive) {
            pendingMode = destination
            showTimelapseNavigationConfirm = true
            return
        }
        activeMode = destination
        when (destination) {
            ModeDestination.CAMERA -> {
                if (hasCameraPermission) screen = AppScreen.CAMERA
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            ModeDestination.TIMELAPSE -> screen = AppScreen.TIMELAPSE_SETUP
            ModeDestination.PREVIEW, ModeDestination.EDIT, ModeDestination.EXPORT -> {
                previewMode = destination
                screen = AppScreen.PREVIEW
                if (destination == ModeDestination.EXPORT) exportRequest++
            }
            ModeDestination.PROJECTS -> screen = AppScreen.GALLERY
            ModeDestination.EFFECTS -> screen = AppScreen.EFFECTS
            ModeDestination.ADVANCED -> screen = AppScreen.EFFECTS
            ModeDestination.SHARE -> shareLatestExportedMovie()
        }
    }

    fun confirmPendingMode() {
        timelapseActive = false
        val destination = pendingMode
        pendingMode = null
        showTimelapseNavigationConfirm = false
        if (destination != null) navigateToMode(destination)
    }

    LaunchedEffect(refresh, activeProject?.id) {
        val store = frameStore
        if (store == null) {
            snapshot = ProjectSnapshot(emptyList(), null)
            return@LaunchedEffect
        }
        val frames = withContext(Dispatchers.IO) { store.frames() }
        val bitmap = frames.lastOrNull()?.file?.let { latest ->
            withContext(Dispatchers.IO) { decodeSampledFrame(latest, targetWidth = 360, targetHeight = 360) }
        }
        snapshot = ProjectSnapshot(frames, bitmap)
    }

    LaunchedEffect(activeProject?.id) {
        val store = frameStore
        if (store == null) { playbackFps = 8; confirmFrameDeletion = true; timelineZoomLevel = 1; return@LaunchedEffect }
        val settings = withContext(Dispatchers.IO) { Triple(store.playbackFps(), store.deleteConfirmationEnabled(), store.timelineZoomLevel()) }
        playbackFps = settings.first
        confirmFrameDeletion = settings.second
        timelineZoomLevel = settings.third
    }

    fun editFrames(block: () -> List<AnimationFrame>, onSuccess: (List<AnimationFrame>) -> Unit = {}) {
        persistenceScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            refresh++
            settingsMessage = if (result.isSuccess) null else "That change could not be saved. Please try again."
            result.getOrNull()?.let(onSuccess)
        }
    }

    fun saveCapture(imageCapture: ImageCapture, onFinished: (Boolean) -> Unit) {
        val store = frameStore ?: run { message = "Choose a movie before taking photos."; onFinished(false); return }
        val destination = runCatching { store.nextFrame() }.getOrElse {
            message = "There is not enough storage to prepare another photo."
            onFinished(false)
            return
        }
        val output = ImageCapture.OutputFileOptions.Builder(destination).build()
        runCatching { imageCapture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                persistenceScope.launch {
                    val failure = runCatching { withContext(Dispatchers.IO) { store.recordCapturedFrame(destination) } }.exceptionOrNull()
                    refresh++
                    message = if (failure == null) null else "Photo saved, but project details could not be updated."
                    onFinished(failure == null)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                destination.delete()
                message = "Photo not saved. Check available storage and try again."
                onFinished(false)
            }
        }) }.onFailure {
            destination.delete()
            message = "Photo could not be saved. Check available storage and try again."
            onFinished(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
    if (!projectsReady && screen != AppScreen.HOME) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center), color = Teal)
    } else when (screen) {
        AppScreen.HOME -> HomeScreen(
            frameCount = snapshot.frames.size,
            latestBitmap = snapshot.latestBitmap,
            permissionDenied = permissionDenied,
            autoAdvanceToGallery = autoAdvanceSplash,
            onContinueAnimation = {
                autoAdvanceSplash = false
                message = null
                activeMode = ModeDestination.PROJECTS
                projectMessage = null
                screen = AppScreen.GALLERY
            },
            onOpenModes = { modesOpen = true },
            onReview = {
                reviewIndex = snapshot.frames.lastIndex
                screen = AppScreen.REVIEW
            }
        )
        AppScreen.GALLERY -> ProjectGalleryScreen(
            projects = projects,
            activeId = activeProjectId,
            busy = projectOperationBusy,
            message = projectMessage,
            viewMode = galleryView,
            onViewModeChange = { selected ->
                galleryView = selected
                context.getSharedPreferences("animation_workshop", Context.MODE_PRIVATE).edit()
                    .putString("project_gallery_view", selected.name).apply()
            },
            onOpen = ::openProject,
            onCreate = { name -> runProjectOperation {
                val created = withContext(Dispatchers.IO) { projectRepository.create(name, currentFolderId) }
                loadGallery(created.id)
                withContext(Dispatchers.IO) { projectRepository.setActiveProjectId(created.id) }
                activeProjectId = created.id
                playbackFps = 8
                refresh++
                activeMode = ModeDestination.CAMERA
                if (hasCameraPermission) screen = AppScreen.CAMERA else permissionLauncher.launch(Manifest.permission.CAMERA)
            } },
            onRename = { project, name -> runProjectOperation {
                withContext(Dispatchers.IO) { projectRepository.rename(project.id, name) }
                loadGallery()
            } },
            onDuplicate = { project, name -> runProjectOperation {
                withContext(Dispatchers.IO) { projectRepository.duplicate(project.id, name) }
                loadGallery()
            } },
            onDelete = { project -> runProjectOperation {
                val updated = withContext(Dispatchers.IO) { projectRepository.delete(project.id) }
                val nextId = projectRepository.activeProjectId() ?: updated.firstOrNull()?.id
                loadGallery(nextId)
                if (activeProjectId == project.id) activeProjectId = nextId
                refresh++
            } },
            onBack = { autoAdvanceSplash = false; screen = AppScreen.HOME },
            allFolders = allFolders,
            currentFolderId = currentFolderId,
            onOpenFolder = { folder -> currentFolderId = folder.id; runProjectOperation { loadGallery() } },
            onOpenParentFolder = { currentFolderId = allFolders.firstOrNull { it.id == currentFolderId }?.parentId; runProjectOperation { loadGallery() } },
            onOpenModes = { activeMode = ModeDestination.PROJECTS; modesOpen = true },
            onCreateFolder = { name, parentId -> runProjectOperation {
                withContext(Dispatchers.IO) { projectRepository.createFolder(name, parentId) }
                allFolders = withContext(Dispatchers.IO) { projectRepository.folders() }
            } },
            onRenameFolder = { folder, name -> runProjectOperation {
                withContext(Dispatchers.IO) { projectRepository.renameFolder(folder.id, name) }
                allFolders = withContext(Dispatchers.IO) { projectRepository.folders() }
            } },
            onMoveProjects = { ids, target -> runProjectOperation {
                withContext(Dispatchers.IO) { projectRepository.moveProjects(ids, target) }
                loadGallery()
            } },
            onMergeProjects = { ids, name, fps, folderId -> runProjectOperation {
                val merged = withContext(Dispatchers.IO) { projectRepository.mergeProjects(ids, name, fps, folderId) }
                currentFolderId = folderId
                loadGallery(merged.id)
                withContext(Dispatchers.IO) { projectRepository.setActiveProjectId(merged.id) }
                activeProjectId = merged.id
                playbackFps = fps
            } },
            onBatchExport = { selected ->
                runProjectOperation {
                    val failures = mutableListOf<String>()
                    selected.forEachIndexed { index, project ->
                        projectMessage = "Exporting ${index + 1} of ${selected.size}: ${project.name}"
                        try {
                            withContext(Dispatchers.IO) {
                                val store = projectRepository.frameStore(project)
                                val frames = store.frames()
                                Mp4Exporter.export(context, frames, Mp4Exporter.inspect(frames, store.playbackFps()), project.id) { percent ->
                                    withContext(Dispatchers.Main) { projectMessage = "Exporting ${index + 1} of ${selected.size}: ${project.name} · $percent%" }
                                }
                            }
                        } catch (failure: Exception) {
                            failures += project.name
                        }
                    }
                    projectMessage = if (failures.isEmpty()) "Finished exporting ${selected.size} movies." else "Finished with ${failures.size} error(s): ${failures.joinToString()}"
                }
            },
            onProjectAction = { project, action ->
                when (action) {
                    ProjectAction.PREVIEW, ProjectAction.EDIT, ProjectAction.EXPORT -> {
                        persistenceScope.launch {
                            withContext(Dispatchers.IO) { projectRepository.setActiveProjectId(project.id) }
                            activeProjectId = project.id
                            playbackFps = withContext(Dispatchers.IO) { projectRepository.frameStore(project).playbackFps() }
                            refresh++
                            previewMode = if (action == ProjectAction.EDIT) ModeDestination.EDIT else ModeDestination.PREVIEW
                            if (action == ProjectAction.EXPORT) exportRequest++
                            screen = AppScreen.PREVIEW
                        }
                    }
                    ProjectAction.SHARE -> persistenceScope.launch {
                        val existing = withContext(Dispatchers.IO) { findProjectExportUri(context, project.id) }
                        if (existing != null) {
                            shareMovieUri(existing)
                        } else {
                            pendingShareProjectId = project.id
                            withContext(Dispatchers.IO) { projectRepository.setActiveProjectId(project.id) }
                            activeProjectId = project.id
                            playbackFps = withContext(Dispatchers.IO) { projectRepository.frameStore(project).playbackFps() }
                            refresh++
                            previewMode = ModeDestination.PREVIEW
                            exportRequest++
                            screen = AppScreen.PREVIEW
                        }
                    }
                    ProjectAction.RENAME, ProjectAction.DUPLICATE, ProjectAction.DELETE -> Unit
                }
            },
            onDeleteProjects = { selected -> runProjectOperation {
                withContext(Dispatchers.IO) { projectRepository.deleteProjects(selected.map { it.id }.toSet()) }
                loadGallery()
            } }
        )
        AppScreen.CAMERA, AppScreen.TIMELAPSE -> {
            val timelapse = if (screen == AppScreen.TIMELAPSE) timelapseConfig else null
            if (!hasCameraPermission) {
                PermissionScreen(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }, onBack = { screen = AppScreen.HOME })
            } else {
                CameraScreen(
                    frameCount = snapshot.frames.size,
                    latestBitmap = snapshot.latestBitmap,
                    message = message,
                    timelapseConfig = timelapse,
                    onBack = { screen = AppScreen.GALLERY },
                    onOpenModes = { modesOpen = true },
                    onTimelapseActiveChange = { timelapseActive = it },
                    onOpenTimelapseSetup = { activeMode = ModeDestination.TIMELAPSE; screen = AppScreen.TIMELAPSE_SETUP },
                    onTimelapseFinished = { timelapseActive = false; activeMode = ModeDestination.CAMERA; screen = AppScreen.CAMERA; timelapseConfig = null },
                    onOpenPreview = { activeMode = ModeDestination.PREVIEW; previewMode = ModeDestination.PREVIEW; screen = AppScreen.PREVIEW },
                    onReview = {
                        reviewIndex = snapshot.frames.lastIndex
                        screen = AppScreen.REVIEW
                    },
                    onCapture = ::saveCapture
                )
            }
        }
        AppScreen.TIMELAPSE_SETUP -> TimelapseSetupScreen(
            onOpenModes = { modesOpen = true },
            onBack = { activeMode = ModeDestination.CAMERA; screen = AppScreen.CAMERA },
            onStart = { config -> activeMode = ModeDestination.TIMELAPSE; timelapseConfig = config; message = null; screen = AppScreen.TIMELAPSE }
        )
        AppScreen.PREVIEW -> AnimationPreviewScreen(
            projectId = activeProject?.id.orEmpty(),
            frames = snapshot.frames,
            fps = playbackFps,
            confirmFrameDeletion = confirmFrameDeletion,
            timelineZoomLevel = timelineZoomLevel,
            settingsMessage = settingsMessage,
            mode = previewMode,
            exportRequest = exportRequest,
            onExportRequestHandled = { exportRequest = 0 },
            onMovieExported = { movie ->
                if (pendingShareProjectId == activeProject?.id) {
                    pendingShareProjectId = null
                    shareMovieUri(movie.uri)
                }
            },
            onExportAbandoned = { pendingShareProjectId = null },
            onOpenModes = { modesOpen = true },
            onFpsChange = { playbackFps = it },
            onSaveFps = { fps ->
                persistenceScope.launch {
                    val store = frameStore ?: return@launch
                    val saveFailure = withContext(Dispatchers.IO) {
                        runCatching { store.savePlaybackFps(fps) }.exceptionOrNull()
                    }
                    settingsMessage = if (saveFailure == null) null else "Speed setting could not be saved."
                }
            },
            onMoveFrame = { id, direction -> frameStore?.let { store -> editFrames({ store.moveFrame(id, direction) }) } },
            onDuplicateFrame = { id, onDuplicated ->
                frameStore?.let { store -> editFrames({ store.duplicateFrame(id) }) { updated ->
                    val index = updated.indexOfFirst { it.id == id }
                    onDuplicated(updated.getOrNull(index + 1)?.id)
                } }
            },
            onDeleteFrames = { ids -> frameStore?.let { store -> editFrames({ store.deleteFrames(ids) }) } },
            onSaveDeleteConfirmation = { enabled ->
                confirmFrameDeletion = enabled
                persistenceScope.launch { withContext(Dispatchers.IO) { frameStore?.saveDeleteConfirmationEnabled(enabled) } }
            },
            onSaveTimelineZoom = { level ->
                timelineZoomLevel = level.coerceIn(0, 2)
                persistenceScope.launch { withContext(Dispatchers.IO) { frameStore?.saveTimelineZoomLevel(timelineZoomLevel) } }
            },
            onDurationChange = { id, duration -> frameStore?.let { store -> editFrames({ store.setDuration(id, duration) }) } },
            onSetAllDurations = { duration -> frameStore?.let { store -> editFrames({ store.setAllDurations(duration) }) } },
            onSplitMovie = { cutIndex, partA, partB ->
                val sourceId = activeProject?.id
                if (sourceId != null) persistenceScope.launch {
                    projectOperationBusy = true
                    val result = withContext(Dispatchers.IO) { runCatching { projectRepository.splitProject(sourceId, cutIndex, partA, partB) } }
                    projectOperationBusy = false
                    if (result.isSuccess) {
                        loadGallery()
                        settingsMessage = "Both new movies were saved. The original is unchanged."
                    } else {
                        settingsMessage = result.exceptionOrNull()?.message ?: "The two movie parts could not be saved. Your original movie is unchanged."
                    }
                }
            },
            onBackToCamera = { activeMode = ModeDestination.CAMERA; screen = AppScreen.CAMERA }
        )
        AppScreen.REVIEW -> {
            val file = snapshot.frames.getOrNull(reviewIndex)?.file
            ReviewScreen(
                image = if (file == snapshot.frames.lastOrNull()?.file) snapshot.latestBitmap else null,
                frameNumber = reviewIndex + 1,
                frameCount = snapshot.frames.size,
                onOpenModes = { activeMode = ModeDestination.PREVIEW; modesOpen = true },
                onBack = { activeMode = ModeDestination.CAMERA; screen = AppScreen.HOME },
                onOpenCamera = {
                    if (hasCameraPermission) screen = AppScreen.CAMERA
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )
        }
        AppScreen.EFFECTS -> EffectsPlaceholderScreen(
            onBack = { screen = AppScreen.GALLERY },
            onOpenModes = { modesOpen = true }
        )
    }

    ModesWheel(visible = modesOpen, active = activeMode, onDismiss = { modesOpen = false }, onSelect = ::navigateToMode)
    BackHandler(enabled = modesOpen) { modesOpen = false }
    if (showTimelapseNavigationConfirm) AlertDialog(
        onDismissRequest = { showTimelapseNavigationConfirm = false; pendingMode = null },
        title = { Text("Stop timelapse and switch?") },
        text = { Text("The current timelapse will stop. Photos already captured will stay in your project.") },
        confirmButton = { TextButton(onClick = ::confirmPendingMode) { Text("Stop and continue") } },
        dismissButton = { TextButton(onClick = { showTimelapseNavigationConfirm = false; pendingMode = null }) { Text("Keep recording") } }
    )
    modeNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { modeNotice = null },
            title = { Text("Animation Workshop / 動畫工作室") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { modeNotice = null }) { Text("OK / 好") } }
        )
    }
    }

}

private enum class AppScreen { HOME, GALLERY, CAMERA, TIMELAPSE_SETUP, TIMELAPSE, PREVIEW, REVIEW, EFFECTS }

private fun findLatestExportedMovieUri(context: Context): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(MediaStore.Video.Media._ID)
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
    val selectionArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/AnimationWorkshop/", "video/mp4")
    return runCatching {
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }.getOrNull()
}

private fun findProjectExportUri(context: Context, projectId: String): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    if (ProjectExportIdentity.fileNamePrefix(projectId) == null) return null
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    return runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf("${Environment.DIRECTORY_MOVIES}/AnimationWorkshop/", "video/mp4"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (ProjectExportIdentity.belongsToProject(name, projectId)) return@use ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
            null
        }
    }.getOrNull()
}

@Composable
private fun HomeScreen(
    frameCount: Int,
    latestBitmap: Bitmap?,
    permissionDenied: Boolean,
    autoAdvanceToGallery: Boolean,
    onContinueAnimation: () -> Unit,
    onOpenModes: () -> Unit,
    onReview: () -> Unit
) {
    val splashAlpha = remember { Animatable(1f) }
    LaunchedEffect(autoAdvanceToGallery) {
        if (autoAdvanceToGallery) {
            delay(2_000)
            splashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 450))
            onContinueAnimation()
        } else {
            splashAlpha.snapTo(1f)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = splashAlpha.value }.background(Color(0xFF8EDBFA))) {
        val scale = minOf(maxWidth.value / 852f, maxHeight.value / 1858f)
        val artWidth = 852f * scale
        val artHeight = 1858f * scale
        val artLeft = (maxWidth.value - artWidth) / 2f
        val artTop = (maxHeight.value - artHeight) / 2f
        fun left(px: Float, width: Float) = (artLeft + px * scale).dp
        fun top(px: Float, height: Float) = (artTop + px * scale).dp
        fun size(px: Float) = (px * scale).dp

        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.little_movie_maker_splash),
            contentDescription = "Little Movie Maker cartoon workshop with children making stop-motion animations",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Transparent semantic hit areas sit over the matching illustrated buttons.
        Box(
            Modifier.offset(x = left(292f, 268f), y = top(48f, 110f)).size(size(268f), size(110f))
                .semantics { contentDescription = "MODES. Open mode selection"; role = Role.Button }
                .clickable(onClick = onOpenModes)
        )
        Box(
            Modifier.offset(x = left(139f, 574f), y = top(1288f, 246f)).size(size(574f), size(246f))
                .semantics { contentDescription = "Continue Animation, 繼續製作動畫"; role = Role.Button }
                .clickable(onClick = onContinueAnimation)
        )
        Box(
            Modifier.offset(x = left(109f, 634f), y = top(1545f, 250f)).size(size(634f), size(250f))
                .semantics { contentDescription = "Your latest frame, frame $frameCount. Tap to review."; role = Role.Button }
                .clickable(enabled = frameCount > 0, onClick = onReview)
        )

        val thumbX = left(151f, 196f)
        val thumbY = top(1563f, 176f)
        Box(
            Modifier.offset(x = thumbX, y = thumbY).size(size(196f), size(176f))
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFFDF5))
        , contentAlignment = Alignment.Center) {
            if (latestBitmap != null) {
                Image(bitmap = latestBitmap.asImageBitmap(), contentDescription = "Latest animation photograph", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("📷\nNo frames yet", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }

        Box(
            Modifier.offset(x = left(352f, 350f), y = top(1627f, 74f)).size(size(350f), size(74f))
                .clip(RoundedCornerShape(8.dp)).background(Color(0xF8FFFDF5)),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (frameCount > 0) "Frame $frameCount  ·  Tap to review" else "No frames yet  ·  Start a movie",
                color = Color(0xFF15566B), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp)
            )
        }

        if (permissionDenied) {
            Text(
                "Camera permission is needed to take pictures.", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xEEFFFDF5)).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Cream).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("📷", fontSize = 58.sp)
        Text("Let’s use the camera", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text("We need camera access so you can take your movie frames.", color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Allow camera") }
        Text("Go back", color = Teal, modifier = Modifier.clickable(onClick = onBack).padding(16.dp))
    }
}

@Composable
private fun EffectsPlaceholderScreen(onBack: () -> Unit, onOpenModes: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFFFFDF5)).windowInsetsPadding(WindowInsets.statusBars).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✨", fontSize = 54.sp)
        Text("Effects / 特效", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
        Text("Coming Soon! / 即將推出！", color = Teal, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Projects / 專案") }
            Button(onClick = onOpenModes, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Coral)) { Text("MODES") }
        }
    }
}

@Composable
private fun CameraScreen(
    frameCount: Int,
    latestBitmap: Bitmap?,
    message: String?,
    timelapseConfig: TimelapseConfig?,
    onOpenModes: () -> Unit,
    onTimelapseActiveChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenTimelapseSetup: () -> Unit,
    onTimelapseFinished: () -> Unit,
    onOpenPreview: () -> Unit,
    onReview: () -> Unit,
    onCapture: (ImageCapture, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturePending by remember { mutableStateOf(false) }
    var timelapseStatus by remember(timelapseConfig) { mutableStateOf(if (timelapseConfig == null) TimelapseStatus.FINISHED else TimelapseStatus.RECORDING) }
    var timelapseCaptured by remember(timelapseConfig) { mutableStateOf(0) }
    var activeElapsedMillis by remember(timelapseConfig) { mutableStateOf(0L) }
    var activeSegmentStart by remember(timelapseConfig) { mutableStateOf(SystemClock.elapsedRealtime()) }
    var elapsedDisplay by remember(timelapseConfig) { mutableStateOf(0L) }
    var hasTakenFirstFrame by remember(timelapseConfig) { mutableStateOf(false) }
    var showLeaveWarning by remember { mutableStateOf(false) }
    var leaveToManual by remember { mutableStateOf(false) }
    val view = LocalView.current
    val toneGenerator = remember(timelapseConfig != null) {
        if (timelapseConfig != null) runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() else null
    }

    fun activeElapsedNow(): Long = activeElapsedMillis + if (timelapseStatus == TimelapseStatus.RECORDING) SystemClock.elapsedRealtime() - activeSegmentStart else 0L
    fun finishTimelapse() {
        if (timelapseStatus == TimelapseStatus.RECORDING) activeElapsedMillis += SystemClock.elapsedRealtime() - activeSegmentStart
        elapsedDisplay = activeElapsedMillis
        timelapseStatus = TimelapseStatus.FINISHED
    }
    fun requestBack(toManual: Boolean = false) {
        if (timelapseConfig != null && timelapseStatus != TimelapseStatus.FINISHED) {
            leaveToManual = toManual
            showLeaveWarning = true
        } else if (toManual) onTimelapseFinished() else onBack()
    }

    LaunchedEffect(timelapseStatus, timelapseConfig) {
        onTimelapseActiveChange(timelapseConfig != null && timelapseStatus != TimelapseStatus.FINISHED)
    }

    BackHandler { requestBack() }
    DisposableEffect(view, timelapseStatus) {
        view.keepScreenOn = timelapseStatus == TimelapseStatus.RECORDING
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(toneGenerator) {
        onDispose { runCatching { toneGenerator?.release() } }
    }
    DisposableEffect(lifecycleOwner, timelapseStatus) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && timelapseStatus == TimelapseStatus.RECORDING) {
                activeElapsedMillis += SystemClock.elapsedRealtime() - activeSegmentStart
                timelapseStatus = TimelapseStatus.PAUSED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(timelapseConfig, timelapseStatus, imageCapture) {
        val config = timelapseConfig ?: return@LaunchedEffect
        if (timelapseStatus != TimelapseStatus.RECORDING || imageCapture == null) return@LaunchedEffect
        val intervalMillis = config.intervalSeconds * 1000L
        var nextCaptureAt = if (hasTakenFirstFrame) SystemClock.elapsedRealtime() + intervalMillis else SystemClock.elapsedRealtime()
        var countdownTarget = Long.MIN_VALUE
        var countdownSecond = 0
        while (isActive && timelapseStatus == TimelapseStatus.RECORDING) {
            val now = SystemClock.elapsedRealtime()
            if (config.stopCondition == TimelapseStopCondition.CLOCK_TIME && System.currentTimeMillis() >= config.stopAtMillis) {
                finishTimelapse(); break
            }
            if (config.stopCondition == TimelapseStopCondition.FRAME_COUNT && timelapseCaptured >= config.frameLimit) {
                finishTimelapse(); break
            }
            val durationTarget = config.durationMinutes * 60_000L
            val durationRemaining = durationTarget - activeElapsedNow()
            if (config.stopCondition == TimelapseStopCondition.RECORDING_DURATION && durationRemaining <= 0L) {
                finishTimelapse(); break
            }
            val untilCapture = (nextCaptureAt - now).coerceAtLeast(0L)
            val clockRemaining = if (config.stopCondition == TimelapseStopCondition.CLOCK_TIME) (config.stopAtMillis - System.currentTimeMillis()).coerceAtLeast(0L) else Long.MAX_VALUE
            val stopRemaining = when (config.stopCondition) {
                TimelapseStopCondition.FRAME_COUNT -> Long.MAX_VALUE
                TimelapseStopCondition.RECORDING_DURATION -> durationRemaining
                TimelapseStopCondition.CLOCK_TIME -> clockRemaining
            }
            val waitFor = minOf(untilCapture, stopRemaining)
            if (untilCapture > 0L) {
                val countdownAllowed = config.countdownSoundEnabled && config.intervalSeconds > 3 && hasTakenFirstFrame && waitFor == untilCapture
                if (countdownAllowed) {
                    if (countdownTarget != nextCaptureAt) {
                        countdownTarget = nextCaptureAt
                        countdownSecond = 3
                    }
                    if (countdownSecond > 0) {
                        val beepAt = countdownTarget - countdownSecond * 1_000L
                        val beepDelay = beepAt - SystemClock.elapsedRealtime()
                        if (beepDelay > 0L) delay(beepDelay)
                        if (timelapseStatus != TimelapseStatus.RECORDING) continue
                        val remainingAtBeep = countdownTarget - SystemClock.elapsedRealtime()
                        val lowerBound = (countdownSecond - 1) * 1_000L
                        val upperBound = countdownSecond * 1_000L
                        if (remainingAtBeep > lowerBound && remainingAtBeep <= upperBound + 150L) {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                        }
                        countdownSecond--
                        continue
                    }
                }
                if (waitFor > 0L) { delay(waitFor); continue }
            }
            val currentCapture = imageCapture ?: break
            if (capturePending) { delay(25); continue }
            capturePending = true
            val succeeded = suspendCancellableCoroutine<Boolean> { continuation ->
                onCapture(currentCapture) { result ->
                    capturePending = false
                    if (continuation.isActive) continuation.resume(result)
                }
                continuation.invokeOnCancellation { capturePending = false }
            }
            if (!succeeded) { finishTimelapse(); break }
            timelapseCaptured++
            hasTakenFirstFrame = true
            nextCaptureAt += intervalMillis
            val after = SystemClock.elapsedRealtime()
            if (nextCaptureAt <= after) nextCaptureAt += (((after - nextCaptureAt) / intervalMillis) + 1L) * intervalMillis
        }
    }
    LaunchedEffect(timelapseStatus, timelapseConfig) {
        val config = timelapseConfig ?: return@LaunchedEffect
        while (isActive && timelapseStatus != TimelapseStatus.FINISHED) {
            elapsedDisplay = activeElapsedNow()
            if (config.stopCondition == TimelapseStopCondition.CLOCK_TIME && System.currentTimeMillis() >= config.stopAtMillis) {
                finishTimelapse(); break
            }
            delay(250)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val captureUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                    .build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, captureUseCase)
                imageCapture = captureUseCase
                cameraError = null
            } catch (_: Exception) {
                cameraError = "Camera could not start. Please try again."
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            cameraProviderFuture.addListener({
                runCatching { cameraProviderFuture.get().unbindAll() }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF182522)).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Text("‹  Back", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = ::requestBack).padding(8.dp))
            Text("MODES", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Teal).clickable(onClick = onOpenModes).padding(horizontal = 17.dp, vertical = 9.dp))
            Text(if (timelapseConfig == null) "FRAME ${frameCount + 1}" else "TIMELAPSE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (timelapseConfig == null) {
                Text("Preview ▶", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onOpenPreview).padding(horizontal = 14.dp, vertical = 5.dp))
                Text("Timelapse ⏱", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Teal).clickable(onClick = onOpenTimelapseSetup).padding(horizontal = 11.dp, vertical = 6.dp))
            } else {
                Text("Manual camera", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Teal).clickable(onClick = { requestBack(toManual = true) }).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            CornerMarkers(Modifier.fillMaxSize().padding(18.dp))
            if (cameraError != null) Text(cameraError!!, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(28.dp))
        }
        if (timelapseConfig != null) {
            val config = timelapseConfig
            val remaining = when (config.stopCondition) {
                TimelapseStopCondition.FRAME_COUNT -> "${(config.frameLimit - timelapseCaptured).coerceAtLeast(0)} frames left"
                TimelapseStopCondition.RECORDING_DURATION -> "${formatTimelapseTime((config.durationMinutes * 60_000L - elapsedDisplay).coerceAtLeast(0L))} left"
                TimelapseStopCondition.CLOCK_TIME -> "${formatTimelapseTime((config.stopAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))} until stop time"
            }
            Text(when (timelapseStatus) { TimelapseStatus.RECORDING -> "● Recording"; TimelapseStatus.PAUSED -> "Ⅱ Paused"; TimelapseStatus.FINISHED -> "✓ Finished" }, color = if (timelapseStatus == TimelapseStatus.RECORDING) Color(0xFFFF8A80) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text("$timelapseCaptured photos  ·  ${formatTimelapseTime(elapsedDisplay)} elapsed", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
            if (message != null) Text(message, color = Color(0xFFFFD3CC), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
            if (timelapseStatus != TimelapseStatus.FINISHED) Text(remaining, color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (timelapseStatus == TimelapseStatus.RECORDING) Button(onClick = { activeElapsedMillis += SystemClock.elapsedRealtime() - activeSegmentStart; elapsedDisplay = activeElapsedMillis; timelapseStatus = TimelapseStatus.PAUSED }, modifier = Modifier.weight(1f).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Pause Ⅱ") }
                if (timelapseStatus == TimelapseStatus.PAUSED) Button(onClick = { activeSegmentStart = SystemClock.elapsedRealtime(); timelapseStatus = TimelapseStatus.RECORDING }, modifier = Modifier.weight(1f).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Resume ▶") }
                if (timelapseStatus != TimelapseStatus.FINISHED) Button(onClick = ::finishTimelapse, modifier = Modifier.weight(1f).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral)) { Text("STOP", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                if (timelapseStatus == TimelapseStatus.FINISHED) Button(onClick = onTimelapseFinished, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Back to camera") }
            }
        } else if (message != null) Text(message, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        else Text(cameraError ?: "Move a little, then take a picture!", color = Color.White.copy(alpha = .85f), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        if (timelapseConfig == null) Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (latestBitmap != null) {
                BitmapImage(latestBitmap, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Color.White, RoundedCornerShape(12.dp)).clickable(onClick = onReview))
            } else {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Text("1st\nphoto", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(Color.White).padding(5.dp).clip(CircleShape).background(Coral).clickable(enabled = imageCapture != null && !capturePending) {
                    imageCapture?.let { capture ->
                        capturePending = true
                        onCapture(capture) { capturePending = false }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(21.dp).clip(CircleShape).background(Color.White.copy(alpha = .92f)))
            }
            Text("${frameCount} 📸", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        }
        else if (timelapseStatus == TimelapseStatus.FINISHED) Text("Your photos are saved in this project.", color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(10.dp))
        else Text("Taking a photo every ${timelapseConfig.intervalSeconds} seconds", color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(10.dp))
    }

    if (showLeaveWarning) AlertDialog(onDismissRequest = { showLeaveWarning = false }, title = { Text("Stop this timelapse?") }, text = { Text("Leaving stops the recording. Photos already taken stay in your project.") }, confirmButton = { TextButton(onClick = { showLeaveWarning = false; finishTimelapse(); if (leaveToManual) onTimelapseFinished() else onBack() }) { Text(if (leaveToManual) "Stop and switch" else "Stop and leave") } }, dismissButton = { TextButton(onClick = { showLeaveWarning = false }) { Text("Keep recording") } })
}

private enum class TimelapseStatus { RECORDING, PAUSED, FINISHED }

private fun formatTimelapseTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%02d:%02d:%02d".format(seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
}

@Composable
private fun CornerMarkers(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val length = 28.dp.toPx()
        val thickness = 4.dp.toPx()
        val inset = 2.dp.toPx()
        val color = Color.White.copy(alpha = .96f)
        val points = listOf(
            Triple(inset, inset, 0),
            Triple(size.width - inset, inset, 1),
            Triple(inset, size.height - inset, 2),
            Triple(size.width - inset, size.height - inset, 3)
        )
        points.forEach { (x, y, corner) ->
            val path = Path().apply {
                when (corner) {
                    0 -> { moveTo(x, y + length); lineTo(x, y); lineTo(x + length, y) }
                    1 -> { moveTo(x - length, y); lineTo(x, y); lineTo(x, y + length) }
                    2 -> { moveTo(x, y - length); lineTo(x, y); lineTo(x + length, y) }
                    else -> { moveTo(x - length, y); lineTo(x, y); lineTo(x, y - length) }
                }
            }
            drawPath(path, color, style = Stroke(width = thickness))
        }
    }
}

@Composable
private fun ReviewScreen(image: Bitmap?, frameNumber: Int, frameCount: Int, onOpenModes: () -> Unit, onBack: () -> Unit, onOpenCamera: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF182522)).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  Back", color = Color.White, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text("Your photo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("MODES", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Teal).clickable(onClick = onOpenModes).padding(horizontal = 8.dp, vertical = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (image != null) BitmapImage(image, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
            else Text("Photo is loading…", color = Color.White)
        }
        Text("Frame $frameNumber of $frameCount", color = Color.White.copy(alpha = .8f), fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
        Button(onClick = onOpenCamera, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
            Text("Back to camera  📷", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BitmapImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop
) {
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Most recent animation photo",
        modifier = modifier,
        contentScale = contentScale
    )
}
