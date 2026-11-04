import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../styles/index.css';
import { ConsentApp } from './ConsentApp';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConsentApp />
  </StrictMode>,
);
