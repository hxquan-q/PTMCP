import { useEffect, useState } from 'react';
import { Button, Input } from 'antd';
import { Alert, Dialog, Field } from '../../../components/common';
import { decryptAudit, type AuditDecryptResult } from '../../../services/access';
import { showToast } from '../../../toast';
import { ApiError } from '../../../services/http';

export function AuditDecryptDialog({
  open,
  requestId,
  onClose,
  onDecrypted,
}: {
  open: boolean;
  requestId: string;
  onClose: () => void;
  onDecrypted?: () => void;
}) {
  const [reason, setReason] = useState('');
  const [fields, setFields] = useState<string[]>(['sqlRaw']);
  const [result, setResult] = useState<AuditDecryptResult | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const valid = reason.trim().length >= 10 && reason.length <= 500 && fields.length > 0;

  // 到期倒计时：到 0 自动清除明文（组件卸载/关闭时同样丢弃）
  useEffect(() => {
    if (!result) return;
    const tick = () => {
      const left = Math.max(0, Math.ceil((new Date(result.expiresAt).getTime() - Date.now()) / 1000));
      setRemaining(left);
      if (left === 0) setResult(null);
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [result]);

  const reset = () => {
    setReason('');
    setFields(['sqlRaw']);
    setResult(null);
    setError('');
  };

  const submit = async () => {
    setError('');
    setLoading(true);
    try {
      const data = await decryptAudit(requestId, reason.trim(), fields);
      setResult(data);
      showToast('解密成功，明文将在 30 秒后自动清除', 'warning');
      onDecrypted?.();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '解密失败，请稍后重试。');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog
      open={open}
      title="审计记录解密"
      onClose={() => {
        reset();
        onClose();
      }}
      width={640}
      footer={
        result ? (
          <Button
            onClick={() => {
              reset();
              onClose();
            }}
          >
            关闭并清除明文
          </Button>
        ) : (
          <>
            <Button onClick={reset} disabled={loading}>
              重置
            </Button>
            <Button type="primary" onClick={submit} disabled={loading || !valid}>
              {loading ? '解密中…' : '确认解密'}
            </Button>
          </>
        )
      }
    >
      <Alert kind="warning" title="操作将被审计">
        解密属于敏感访问，需要填写排查理由（10～500 字符），系统记录访问人、理由与字段；明文仅短时可见，到期自动清除。
      </Alert>
      <Field label={`排查理由（${reason.length}/500）`} hint="至少 10 个字符">
        <Input.TextArea
          value={reason}
          rows={3}
          style={{ width: '100%' }}
          maxLength={500}
          disabled={loading || !!result}
          onChange={(e) => setReason(e.target.value)}
          placeholder="例如：用户反馈工单 #123 查询被拒，需核对实际执行的 SQL 与 guard 重写差异。"
        />
      </Field>
      <Field label="解密字段">
        <div style={{ display: 'flex', gap: 16 }}>
          {['sqlRaw', 'sqlFinal'].map((field) => (
            <label key={field} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={fields.includes(field)}
                disabled={loading || !!result}
                onChange={() => setFields((s) => (s.includes(field) ? s.filter((f) => f !== field) : [...s, field]))}
              />
              <code>{field}</code>
              <span style={{ color: 'var(--ptmcp-text-muted)' }}>{field === 'sqlRaw' ? '原始请求' : '执行语句'}</span>
            </label>
          ))}
        </div>
      </Field>
      {error ? <Alert kind="danger">{error}</Alert> : null}
      {result ? (
        <div role="status" aria-live="polite">
          <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
            明文已解锁。剩余 {remaining} 秒，到期自动清除（expiresAt：{result.expiresAt}）。
          </p>
          {(['sqlRaw', 'sqlFinal'] as const).map((field) =>
            result[field] ? (
              <div key={field} style={{ marginTop: 8 }}>
                <strong style={{ display: 'block', fontSize: 12, color: 'var(--ptmcp-text-muted)', marginBottom: 4 }}>{field}</strong>
                <pre
                  style={{
                    margin: 0,
                    padding: '12px 14px',
                    background: 'var(--ptmcp-chrome)',
                    border: '1px solid var(--ptmcp-border)',
                    borderRadius: 'var(--ptmcp-radius-control)',
                    fontSize: 12.5,
                    lineHeight: 1.6,
                    whiteSpace: 'pre-wrap',
                    overflow: 'auto',
                    fontFamily: "ui-monospace, 'SF Mono', Consolas, monospace",
                  }}
                >
                  {result[field]}
                </pre>
              </div>
            ) : null,
          )}
        </div>
      ) : null}
    </Dialog>
  );
}

/** 元数据字典编辑：对象级标题/说明/负责人 + 按列注释；If-Match 提交，412 走三方 diff + 变基。 */
