import React from 'react';
import ReactDOM from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import App from './App.jsx';
import HomePage from './pages/HomePage.jsx';
import CouponPage from './pages/CouponPage.jsx';
import MoviesPage from './pages/MoviesPage.jsx';
import SeatsPage from './pages/SeatsPage.jsx';
import PaymentPage from './pages/PaymentPage.jsx';
import SuccessPage from './pages/SuccessPage.jsx';
import HistoryPage from './pages/HistoryPage.jsx';
import WaitPage from './pages/WaitPage.jsx'; // WaitPage 임포트
import './index.css';

const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'coupon', element: <CouponPage /> },
      { path: 'movies', element: <MoviesPage /> },
      { path: 'seats', element: <SeatsPage /> },
      { path: 'payment', element: <PaymentPage /> },
      { path: 'success', element: <SuccessPage /> },
      { path: 'history', element: <HistoryPage /> },
      { path: 'wait', element: <WaitPage /> }, // wait 경로 추가
    ],
  },
]);

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>
);