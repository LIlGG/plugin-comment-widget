import { ofetch } from 'ofetch';

export interface UploadedImage {
  uploadId: string;
  url: string;
  expiresAt: string;
}

type SubmissionState =
  | 'ISSUED'
  | 'PREPARING'
  | 'PROCESSING'
  | 'UNKNOWN'
  | 'BOUND'
  | 'FAILED';
interface PendingSubmission {
  id: string;
  fingerprint: string;
}

// Undefined means a previous submission was confirmed, without posting it again.
export type SubmittedResource<T> = T | undefined;

export class UploadSession {
  readonly token = Array.from(crypto.getRandomValues(new Uint8Array(32)))
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('');
  private pending?: PendingSubmission;

  async submit<T>(
    url: string,
    body: unknown,
    ids: string[],
    headers: Record<string, string>,
    baseUrl: string
  ): Promise<SubmittedResource<T>> {
    const fingerprint = JSON.stringify({ url, body, ids });
    if (await this.recoverSubmission(fingerprint, baseUrl)) {
      return undefined;
    }
    if (!ids.length) {
      return ofetch<T>(url, {
        method: 'POST',
        body: body as Record<string, unknown>,
        headers,
        retry: 0,
      });
    }
    const pending = await this.ensureTicket(fingerprint, baseUrl);
    return this.postManaged<T>(url, body, ids, headers, pending.id);
  }

  private async recoverSubmission(
    fingerprint: string,
    baseUrl: string
  ): Promise<boolean> {
    const pending = this.pending;
    if (!pending) {
      return false;
    }
    const status = await this.readStatus(pending.id, baseUrl);
    if (status.state === 'BOUND') {
      this.requireMatchingContent(
        pending,
        fingerprint,
        '上次评论已提交成功；当前内容已修改，请先保留修改内容。'
      );
      return true;
    }
    if (status.state === 'ISSUED') {
      this.requireMatchingContent(
        pending,
        fingerprint,
        '上次提交尚未确认，请先恢复原内容后重试。'
      );
      return false;
    }
    if (status.state === 'FAILED') {
      this.pending = undefined;
      return false;
    }
    throw new Error('上次提交结果正在确认，请稍后重试；图片会被保留。');
  }

  private requireMatchingContent(
    pending: PendingSubmission,
    fingerprint: string,
    message: string
  ) {
    if (fingerprint !== pending.fingerprint) {
      throw new Error(message);
    }
  }

  private async readStatus(id: string, baseUrl: string) {
    return ofetch<{ state: SubmissionState }>(
      `${baseUrl}/apis/api.commentwidget.halo.run/v1alpha1/submissions/${id}`,
      { headers: { 'X-Comment-Upload-Token': this.token }, retry: 0 }
    ).catch((error) => {
      if ([404, 410].includes(error?.response?.status)) {
        throw new Error(
          '上次提交凭证已失效，请核对评论是否成功；此请求不会重新提交。'
        );
      }
      throw error;
    });
  }

  private async ensureTicket(
    fingerprint: string,
    baseUrl: string
  ): Promise<PendingSubmission> {
    if (this.pending) {
      return this.pending;
    }
    const ticket = await ofetch<{ id: string; expiresAt: string }>(
      `${baseUrl}/apis/api.commentwidget.halo.run/v1alpha1/submissions`,
      {
        method: 'POST',
        headers: { 'X-Comment-Upload-Token': this.token },
        retry: 0,
      }
    );
    this.pending = { id: ticket.id, fingerprint };
    return this.pending;
  }

  private postManaged<T>(
    url: string,
    body: unknown,
    ids: string[],
    headers: Record<string, string>,
    id: string
  ) {
    return ofetch<T>(url, {
      method: 'POST',
      body: body as Record<string, unknown>,
      retry: 0,
      headers: {
        ...headers,
        'X-Comment-Upload-Token': this.token,
        'X-Comment-Submission': id,
        'X-Comment-Uploads': ids.join(','),
      },
    }).catch((error) => {
      if (
        [400, 401, 403, 404, 413, 422, 429].includes(error?.response?.status)
      ) {
        this.pending = undefined;
      }
      throw error;
    });
  }
}
