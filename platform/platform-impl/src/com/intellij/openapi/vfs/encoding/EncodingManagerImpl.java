// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.encoding;

import com.intellij.concurrency.JobSchedulerImpl;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.SettingsCategory;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppJavaExecutorUtil;
import com.intellij.util.concurrency.CoroutineDispatcherBackedExecutor;
import com.intellij.util.xmlb.annotations.Attribute;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.*;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApiStatus.Internal
@State(name = "Encoding", storages = @Storage("encoding.xml"), category = SettingsCategory.CODE)
public final class EncodingManagerImpl extends EncodingManager implements PersistentStateComponent<EncodingManagerImpl.State>, Disposable {
  private static final Logger LOG = Logger.getInstance(EncodingManagerImpl.class);

  @ApiStatus.Internal
  public static final class State {
    private @NotNull EncodingReference myDefaultEncoding = new EncodingReference(StandardCharsets.UTF_8);
    private @NotNull EncodingReference myDefaultConsoleEncoding = EncodingReference.DEFAULT;

    @Attribute("default_encoding")
    public @NotNull String getDefaultCharsetName() {
      return myDefaultEncoding.getCharset() == null ? "" : myDefaultEncoding.getCharset().name();
    }

    public void setDefaultCharsetName(@NotNull String name) {
      myDefaultEncoding = new EncodingReference(StringUtil.nullize(name));
    }

    @Attribute("default_console_encoding")
    public @NotNull String getDefaultConsoleEncodingName() {
      return myDefaultConsoleEncoding.getCharset() == null ? "" : myDefaultConsoleEncoding.getCharset().name();
    }

    public void setDefaultConsoleEncodingName(@NotNull String name) {
      myDefaultConsoleEncoding = new EncodingReference(StringUtil.nullize(name));
    }
  }

  private State myState = new State();

  private static final Key<Charset> CACHED_CHARSET_FROM_CONTENT = Key.create("CACHED_CHARSET_FROM_CONTENT");

  private final CoroutineDispatcherBackedExecutor changedDocumentExecutor;

  private final AtomicBoolean myDisposed = new AtomicBoolean();

  public EncodingManagerImpl(@NotNull CoroutineScope coroutineScope) {
    changedDocumentExecutor = AppJavaExecutorUtil.createBoundedTaskExecutor(
      "EncodingManagerImpl Document Pool",
      coroutineScope,
      JobSchedulerImpl.getJobPoolParallelism()
    );

    ApplicationManager.getApplication().getMessageBus().connect(coroutineScope).subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
      @Override
      public void appClosing() {
        // should call before dispose in write action
        // prevent any further re-detection and wait for the queue to clear
        myDisposed.set(true);
        clearDocumentQueue();
      }
    });

    EditorFactory editorFactory = EditorFactory.getInstance();
    editorFactory.getEventMulticaster().addDocumentListener(new DocumentListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent e) {
        Document document = e.getDocument();
        if (isEditorOpenedFor(document)) {
          queueUpdateEncodingFromContent(document);
        }
      }
    }, this);
    editorFactory.addEditorFactoryListener(new EditorFactoryListener() {
      @Override
      public void editorCreated(@NotNull EditorFactoryEvent event) {
        queueUpdateEncodingFromContent(event.getEditor().getDocument());
      }
    }, this);
  }

  private static boolean isEditorOpenedFor(@NotNull Document document) {
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null) return false;
    Project project = guessProject(virtualFile);
    return project != null && !project.isDisposed() && FileEditorManager.getInstance(project).isFileOpen(virtualFile);
  }

  public static final @NonNls String PROP_CACHED_ENCODING_CHANGED = "cachedEncoding";

  private void handleDocument(final @NotNull Document document) {
    VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
    if (virtualFile == null) return;
    Project project = guessProject(virtualFile);
    while(true) {
      if (project != null && project.isDisposed()) break;
      int nRequests = addNumberOfRequestedRedetects(document, 0);
      Charset charset = LoadTextUtil.charsetFromContentOrNull(project, virtualFile, document.getImmutableCharSequence());
      Charset oldCached = getCachedCharsetFromContent(document);
      if (!Comparing.equal(charset, oldCached)) {
        setCachedCharsetFromContent(charset, oldCached, document);
      }
      if (addNumberOfRequestedRedetects(document, -nRequests) == 0) break;
    }
  }

  private static void setCachedCharsetFromContent(Charset charset, Charset oldCached, @NotNull Document document) {
    document.putUserData(CACHED_CHARSET_FROM_CONTENT, charset);
    firePropertyChange(document, PROP_CACHED_ENCODING_CHANGED, oldCached, charset);
  }

  @ApiStatus.Internal
  @VisibleForTesting
  public static @Nullable("returns null if charset set cannot be determined from content") Charset computeCharsetFromContent(final @NotNull VirtualFile virtualFile) {
    final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
    if (document == null) {
      return null;
    }
    Charset cached = getInstance().getCachedCharsetFromContent(document);
    if (cached != null) {
      return cached;
    }

    final Project project = ProjectLocator.getInstance().guessProjectForFile(virtualFile);
    return ReadAction.compute(() -> {
      Charset charsetFromContent = LoadTextUtil.charsetFromContentOrNull(project, virtualFile, document.getImmutableCharSequence());
      if (charsetFromContent != null) {
        setCachedCharsetFromContent(charsetFromContent, null, document);
      }
      return charsetFromContent;
    });
  }

  @Override
  public void dispose() {
    myDisposed.set(true);
  }

  // stores number of re-detection requests for this document
  private static final Key<AtomicInteger> RUNNING_REDETECTS_KEY = Key.create("DETECTING_ENCODING_KEY");

  private static int addNumberOfRequestedRedetects(@NotNull Document document, int delta) {
    AtomicInteger data = document.getUserData(RUNNING_REDETECTS_KEY);
    if (data == null) {
      data = ((UserDataHolderEx)document).putUserDataIfAbsent(RUNNING_REDETECTS_KEY, new AtomicInteger());
    }
    return data.addAndGet(delta);
  }

  @VisibleForTesting
  public void queueUpdateEncodingFromContent(@NotNull Document document) {
    if (myDisposed.get()) return; // ignore re-detect requests on app close
    if (addNumberOfRequestedRedetects(document, 1) == 1) {
      changedDocumentExecutor.execute(new DocumentEncodingDetectRequest(document, myDisposed));
    }
  }

  private static final class DocumentEncodingDetectRequest implements Runnable {
    private final Reference<Document> ref;
    private final @NotNull AtomicBoolean myDisposed;

    private DocumentEncodingDetectRequest(@NotNull Document document, @NotNull AtomicBoolean disposed) {
      ref = new WeakReference<>(document);
      myDisposed = disposed;
    }

    @Override
    public void run() {
      if (myDisposed.get()) return;
      Document document = ref.get();
      if (document == null) return; // document gced, don't bother
      ((EncodingManagerImpl)getInstance()).handleDocument(document);
    }
  }

  @Override
  public @Nullable Charset getCachedCharsetFromContent(@NotNull Document document) {
    return document.getUserData(CACHED_CHARSET_FROM_CONTENT);
  }

  @Override
  public @NotNull State getState() {
    return myState;
  }

  @Override
  public void loadState(@NotNull State state) {
    myState = state;
  }

  @Override
  public @NotNull Collection<Charset> getFavorites() {
    Collection<Charset> result = new HashSet<>();
    Project[] projects = ProjectManager.getInstance().getOpenProjects();
    for (Project project : projects) {
      result.addAll(EncodingProjectManager.getInstance(project).getFavorites());
    }
    result.addAll(EncodingProjectManagerImpl.widelyKnownCharsets());
    return result;
  }

  @Override
  public @Nullable Charset getEncoding(@Nullable VirtualFile virtualFile, boolean useParentDefaults) {
    Project project = guessProject(virtualFile);
    if (project == null) return null;
    EncodingProjectManager encodingManager = EncodingProjectManager.getInstance(project);
    if (encodingManager == null) return null; //tests
    return encodingManager.getEncoding(virtualFile, useParentDefaults);
  }

  public void clearDocumentQueue() {
    if (changedDocumentExecutor.isEmpty()) {
      return;
    }

    if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
      throw new IllegalStateException("Must not call clearDocumentQueue() from under write action because some queued detectors require read action");
    }

    // after clear and canceling all queued tasks, make sure they all are finished
    //noinspection TestOnlyProblems
    changedDocumentExecutor.cancelAndWaitAllTasksExecuted(1, TimeUnit.MINUTES);
  }

  @TestOnly
  public void waitAllTasksExecuted() {
    try {
      changedDocumentExecutor.waitAllTasksExecuted(1, TimeUnit.MINUTES);
    }
    catch (Exception e) {
      LOG.error(e);
    }
  }

  private static @Nullable Project guessProject(@Nullable VirtualFile virtualFile) {
    Project project = virtualFile == null ? null : ProjectLocator.getInstance().guessProjectForFile(virtualFile);
    if (project != null) {
      return project;
    }
    ProjectManager projectManager = ProjectManager.getInstanceIfCreated();
    if (projectManager == null) {
      return null;
    }

    Project[] openProjects = projectManager.getOpenProjects();
    if (openProjects.length == 1) {
      return openProjects[0];
    }
    return null;
  }

  @Override
  public void setEncoding(@Nullable VirtualFile virtualFileOrDir, @Nullable Charset charset) {
    Project project = guessProject(virtualFileOrDir);
    if (project != null) {
      EncodingProjectManager.getInstance(project).setEncoding(virtualFileOrDir, charset);
    }
  }

  @Override
  public boolean isNative2Ascii(final @NotNull VirtualFile virtualFile) {
    Project project = guessProject(virtualFile);
    return project != null && EncodingProjectManager.getInstance(project).isNative2Ascii(virtualFile);
  }

  @Override
  public boolean isNative2AsciiForPropertiesFiles() {
    Project project = guessProject(null);
    return project != null && EncodingProjectManager.getInstance(project).isNative2AsciiForPropertiesFiles();
  }

  @Override
  public void setNative2AsciiForPropertiesFiles(final VirtualFile virtualFile, final boolean native2Ascii) {
    Project project = guessProject(virtualFile);
    if (project == null) return;
    EncodingProjectManager.getInstance(project).setNative2AsciiForPropertiesFiles(virtualFile, native2Ascii);
  }

  @Override
  public @NotNull Charset getDefaultCharset() {
    return myState.myDefaultEncoding.dereference();
  }

  @Override
  public @NotNull String getDefaultCharsetName() {
    return myState.getDefaultCharsetName();
  }

  @Override
  public void setDefaultCharsetName(@NotNull String name) {
    myState.setDefaultCharsetName(name);
  }

  @Override
  public @Nullable Charset getDefaultCharsetForPropertiesFiles(final @Nullable VirtualFile virtualFile) {
    Project project = guessProject(virtualFile);
    if (project == null) return null;
    return EncodingProjectManager.getInstance(project).getDefaultCharsetForPropertiesFiles(virtualFile);
  }

  @Override
  public void setDefaultCharsetForPropertiesFiles(final @Nullable VirtualFile virtualFile, final Charset charset) {
    Project project = guessProject(virtualFile);
    if (project == null) return;
    EncodingProjectManager.getInstance(project).setDefaultCharsetForPropertiesFiles(virtualFile, charset);
  }

  @Override
  public @NotNull Charset getDefaultConsoleEncoding() {
    return myState.myDefaultConsoleEncoding.dereference();
  }

  /**
   * @return default console encoding reference
   */
  public @NotNull EncodingReference getDefaultConsoleEncodingReference() {
    return myState.myDefaultConsoleEncoding;
  }

  /**
   * @param encodingReference default console encoding reference
   */
  public void setDefaultConsoleEncodingReference(@NotNull EncodingReference encodingReference) {
    myState.myDefaultConsoleEncoding = encodingReference;
  }

  static void firePropertyChange(@Nullable Document document, @NotNull String propertyName, Object oldValue, Object newValue) {
    EncodingManagerListener publisher = ApplicationManager.getApplication().getMessageBus()
      .syncPublisher(EncodingManagerListener.ENCODING_MANAGER_CHANGES);
    publisher.propertyChanged(document, propertyName, oldValue, newValue);
  }
}
