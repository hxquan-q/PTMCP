import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../styles/index.css';
import { LoginApp } from './LoginApp';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <LoginApp />
  </StrictMode>,
);
