import type { Editor } from '@tiptap/core';
import Image from '@tiptap/extension-image';
import type { Node } from '@tiptap/pm/model';
import { ToastManager } from '../lit-toast';
import { uploadFiles } from '../utils/upload-api';
import { applyUploadResults } from '../utils/upload-results';
import { type UploadedImage, UploadSession } from '../utils/upload-session';
import { ImageUploadState } from './image-upload-state';

type FileProps = { file: File; editor: Editor };

const blobUrls = new WeakMap<Editor, Set<string>>();
const sessions = new WeakMap<Editor, UploadSession>();
const imageStates = new WeakMap<Editor, ImageUploadState>();

export function imageUploadState(editor: Editor) {
  let state = imageStates.get(editor);
  if (!state) {
    state = new ImageUploadState();
    imageStates.set(editor, state);
  }
  return state;
}
export function uploadSession(editor: Editor): UploadSession {
  let session = sessions.get(editor);
  if (!session) {
    session = new UploadSession();
    sessions.set(editor, session);
  }
  return session;
}
export function resetUploadSession(editor: Editor) {
  sessions.delete(editor);
  imageStates.delete(editor);
  blobUrls.get(editor)?.forEach((url) => {
    URL.revokeObjectURL(url);
  });
  blobUrls.delete(editor);
}
export function uploadedIds(editor: Editor): string[] {
  const ids = new Set<string>();
  editor.state.doc.descendants((node) => {
    if (node.attrs.uploadId) {
      ids.add(node.attrs.uploadId);
    }
  });
  return [...ids];
}

function getFileBlobUrl(file: File) {
  return URL.createObjectURL(file);
}

export function renderImage({ file, editor }: FileProps) {
  const { view } = editor;
  const blobUrl = getFileBlobUrl(file);
  imageUploadState(editor).rememberLocal(blobUrl, file);
  if (!blobUrls.has(editor)) {
    blobUrls.set(editor, new Set());
  }
  blobUrls.get(editor)?.add(blobUrl);

  const node = view.props.state.schema.nodes[Image.name].create({
    src: blobUrl,
    file: file,
    local: true,
  });
  editor.view.dispatch(editor.view.state.tr.replaceSelectionWith(node));
}

type LocalNode = {
  node: Node;
  pos: number;
  parent: Node | null;
  index: number;
};

export function getLocalNodes(editor: Editor) {
  const { state } = editor;
  const localNodes: LocalNode[] = [];
  state.doc.descendants(
    (node: Node, pos: number, parent: Node | null, index: number) => {
      if (node.attrs.local) {
        localNodes.push({ node, pos, parent, index });
      }
    }
  );
  return localNodes;
}

async function uploadFileAndReplaceNode(
  editor: Editor,
  nodes: LocalNode[],
  baseUrl?: string
) {
  try {
    const files = Array.from(nodes).map((node) => node.node.attrs.file);
    const attachments = await uploadFiles(
      files,
      uploadSession(editor),
      baseUrl
    );
    if (attachments.length !== nodes.length) {
      throw new Error('上传结果不完整，请重试');
    }
    applyUploadResults(attachments, (index, attachment) => {
      replaceUploadedImage(editor, nodes[index], attachment);
    });
    return true;
  } catch (error) {
    const toastManager = new ToastManager();
    toastManager.error(imageErrorMessage(error));
  }

  return false;
}

/**
 * Upload all local images in the editor to the server
 * @param editor - The TipTap editor instance
 * @param baseUrl - The base URL for the upload endpoint
 * @returns Promise<boolean> - True if upload succeeds or no files to upload, false otherwise
 */
export async function uploadEditorFiles(
  editor: Editor | undefined,
  baseUrl?: string
): Promise<boolean> {
  if (!editor) {
    return true;
  }

  const restored = imageUploadState(editor).restoreUploaded(editor.state.tr);
  if (restored.docChanged) {
    editor.view.dispatch(restored.setMeta('addToHistory', false));
  }
  const localNodes = getLocalNodes(editor);
  if (localNodes.length === 0) {
    return true;
  }

  const uniqueNodes = new Map<string, LocalNode>();
  for (const node of localNodes) {
    uniqueNodes.set(node.node.attrs.src, node);
  }
  return await uploadFileAndReplaceNode(
    editor,
    [...uniqueNodes.values()],
    baseUrl
  );
}

function replaceUploadedImage(
  editor: Editor,
  original: LocalNode | undefined,
  attachment: UploadedImage
) {
  if (!original || editor.isDestroyed) {
    return;
  }
  imageUploadState(editor).rememberUploaded(
    original.node.attrs.src,
    attachment
  );
  // Locate by blob URL again: positions may have moved during the upload.
  const matches: number[] = [];
  editor.state.doc.descendants((node, pos) => {
    if (node.attrs.local && node.attrs.src === original.node.attrs.src) {
      matches.push(pos);
    }
  });
  let tr = editor.state.tr;
  for (const pos of matches) {
    tr = tr
      .setNodeAttribute(pos, 'src', attachment.url)
      .setNodeAttribute(pos, 'uploadId', attachment.uploadId)
      .setNodeAttribute(pos, 'local', false)
      .setNodeAttribute(pos, 'file', null);
  }
  editor.view.dispatch(tr.setMeta('addToHistory', false));
  // Keep the blob URL valid for undo; it is released on editor destruction.
}

function imageErrorMessage(error: unknown) {
  if (error instanceof Error) {
    return error.message;
  }
  return '未知错误';
}
