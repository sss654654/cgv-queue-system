import React from 'react';
import { useEffect } from 'react';
import { Outlet } from 'react-router-dom';
import Navigation from './components/common/Navigation';
import { AppProvider } from './context/AppContext';
import { issueSession } from './api/api';

function App() {
  // 앱 시작 시 세션 발급 API를 호출합니다.
  useEffect(() => {
    issueSession();
  }, []); // [] 의존성 배열로 최초 1회만 실행

  return (
    <AppProvider>
      <Navigation />
      <main style={{ paddingTop: '80px' }}>
        <Outlet />
      </main>
    </AppProvider>
  );
}

export default App;