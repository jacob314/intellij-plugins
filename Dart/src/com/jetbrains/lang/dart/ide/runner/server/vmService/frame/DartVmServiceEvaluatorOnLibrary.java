package com.jetbrains.lang.dart.ide.runner.server.vmService.frame;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.ExpressionInfo;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.jetbrains.lang.dart.ide.runner.server.vmService.DartVmServiceDebugProcess;
import com.jetbrains.lang.dart.psi.DartFile;
import com.jetbrains.lang.dart.psi.DartPartOfStatement;
import gnu.trove.THashSet;
import org.dartlang.vm.service.consumer.GetIsolateConsumer;
import org.dartlang.vm.service.element.Isolate;
import org.dartlang.vm.service.element.LibraryRef;
import org.dartlang.vm.service.element.RPCError;
import org.dartlang.vm.service.element.Sentinel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class DartVmServiceEvaluatorOnLibrary extends XDebuggerEvaluator {
  @NotNull private final DartVmServiceDebugProcess myDebugProcess;
  @NotNull private final String myIsolateId;
  @NotNull private final VirtualFile myFile;
  @NotNull private final Project myProject;

  public DartVmServiceEvaluatorOnLibrary(@NotNull final DartVmServiceDebugProcess debugProcess,
                                         @NotNull final String isolateId,
                                         @NotNull final VirtualFile file,
                                         @NotNull final Project project) {
    myDebugProcess = debugProcess;
    myIsolateId = isolateId;
    myFile = file;
    myProject = project;
  }

  @Override
  public boolean isCodeFragmentEvaluationSupported() {
    return true;
  }

  Library findLibrary(VirtualFile file, Project project) {
    final LibraryTable projectLibraryTable = ProjectLibraryTable.getInstance(project);

    for (Library library : projectLibraryTable.getLibraries()) {
      for (VirtualFile libraryFile : library.getFiles(OrderRootType.SOURCES)) {
        if (libraryFile.getPath().equals(file.getPath())) {
          return library;
        }
      }
    }
    return null;
  }

  private static VirtualFile getLibraryFile(PsiFile psiFile) {
    if (!(psiFile instanceof DartFile)) {
      return null;
    }
    DartFile dartFile = (DartFile)psiFile;
    DartPartOfStatement partOfStatement = findPart(dartFile);
    PsiFile libraryFile;
    if (partOfStatement != null) {
      if (partOfStatement.getLibraryId() == null) {
        return null;
      }
      PsiElement libraryElement = partOfStatement.getLibraryId().resolve();
      if (libraryElement == null) {
        return null;
      }
      return libraryElement.getContainingFile().getVirtualFile();
    }
    else {
      return psiFile.getVirtualFile();
    }
  }

  @Override
  public void evaluate(@NotNull final String expression,
                       @NotNull final XEvaluationCallback callback,
                       @Nullable final XSourcePosition expressionPosition) {
    VirtualFile libraryFile = getLibraryFile(PsiManager.getInstance(myProject).findFile(myFile));


    myDebugProcess.getVmServiceWrapper().getIsolate(myIsolateId, new GetIsolateConsumer() {

      @Override
      public void onError(RPCError error) {
        callback.errorOccurred(error.toString());
      }

      @Override
      public void received(Isolate response) {
        LibraryRef libraryRef = findMatchingLibrary(response, libraryFile);
        myDebugProcess.getVmServiceWrapper().evaluateInTargetContext(myIsolateId, libraryRef.getId(), expression, callback);
      }

      @Override
      public void received(Sentinel response) {
        callback.errorOccurred(response.toString());
      }
    });
  }

  private static DartPartOfStatement findPart(PsiElement element) {
    if (element instanceof DartPartOfStatement) {
      return (DartPartOfStatement)element;
    }
    for (PsiElement child : element.getChildren()) {
      DartPartOfStatement result = findPart(child);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  private LibraryRef findMatchingLibrary(Isolate isolate, VirtualFile libraryFile) {
    if (libraryFile != null) {
      Set<String> uris = new THashSet<>();
      uris.addAll(myDebugProcess.getUrisForFile(libraryFile));

      for (LibraryRef library : isolate.getLibraries()) {
        if (uris.contains(library.getUri())) {
          return library;
        }
      }
    }
    return isolate.getRootLib();
  }

  @Nullable
  @Override
  public ExpressionInfo getExpressionInfoAtOffset(@NotNull final Project project,
                                                  @NotNull final Document document,
                                                  final int offset,
                                                  final boolean sideEffectsAllowed) {
    final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    final PsiElement contextElement = psiFile == null ? null : psiFile.findElementAt(offset);
    return contextElement == null ? null : DartVmServiceEvaluator.getExpressionInfo(contextElement);
  }
}
