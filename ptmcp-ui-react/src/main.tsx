import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { StyleProvider } from '@ant-design/cssinjs';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import './styles/index.css';
import { App } from './App';

/* Ant Design 6 × Tailwind 4 官方共存方案：layer 顺序在 CSS 顶部声明（theme, base, antd, components,
   utilities），StyleProvider layer 让 antd 样式注入 antd 层——Tailwind 工具类始终能覆盖 antd，
   antd 又压过 Tailwind preflight。主色用 #0f62d6：#1677ff 对白字只有 4.1:1，不达 WCAG AA。 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <StyleProvider layer>
      <ConfigProvider
        locale={zhCN}
        theme={{
          token: {
            colorPrimary: '#0f62d6',
            colorInfo: '#0f62d6',
            colorLink: '#0958d9',
            colorSuccess: '#15803d',
            colorWarning: '#d48806',
            colorError: '#d4380d',
            borderRadius: 8,
            fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
            fontSize: 14,
          },
        }}
      >
        <App />
      </ConfigProvider>
    </StyleProvider>
  </StrictMode>,
);
