import React from 'react';
import { createContext, useReducer } from 'react';

const AppContext = createContext();

const initialState = {
  // 쿠폰 관련 상태
  coupon: {
    totalIssued: 0,
    maxCoupons: 4500000,
    userCoupon: null
  },
  
  // 예매 과정 상태
  booking: {
    selectedMovie: null,
    selectedTheater: null,
    selectedTime: null,
    selectedSeats: [],
    totalPrice: 0
  },
  
  // 예매 내역
  bookingHistory: []
};

function appReducer(state, action) {
  switch (action.type) {
    case 'ISSUE_COUPON':
      return {
        ...state,
        coupon: {
          ...state.coupon,
          totalIssued: state.coupon.totalIssued + 1,
          userCoupon: {
            id: Date.now(),
            issuedAt: new Date(),
            discount: action.payload.discount
          }
        }
      };
      
    case 'SELECT_MOVIE':
      return {
        ...state,
        booking: {
          ...state.booking,
          selectedMovie: action.payload,
          selectedTheater: null,
          selectedTime: null,
          selectedSeats: []
        }
      };
      
    case 'SELECT_THEATER_TIME':
      return {
        ...state,
        booking: {
          ...state.booking,
          selectedTheater: action.payload.theater,
          selectedTime: action.payload.time
        }
      };
      
    case 'TOGGLE_SEAT':
      const seatId = action.payload;
      const currentSeats = state.booking.selectedSeats;
      const isSelected = currentSeats.includes(seatId);
      
      return {
        ...state,
        booking: {
          ...state.booking,
          selectedSeats: isSelected
            ? currentSeats.filter(id => id !== seatId)
            : [...currentSeats, seatId],
          totalPrice: isSelected
            ? state.booking.totalPrice - 12000
            : state.booking.totalPrice + 12000
        }
      };
      
    case 'COMPLETE_BOOKING':
      const newBooking = {
        id: Date.now(),
        movie: state.booking.selectedMovie,
        theater: state.booking.selectedTheater,
        time: state.booking.selectedTime,
        seats: state.booking.selectedSeats,
        totalPrice: state.booking.totalPrice,
        bookedAt: new Date(),
        usedCoupon: action.payload.usedCoupon
      };
      
      return {
        ...state,
        bookingHistory: [...state.bookingHistory, newBooking],
        booking: {
          selectedMovie: null,
          selectedTheater: null,
          selectedTime: null,
          selectedSeats: [],
          totalPrice: 0
        },
        coupon: action.payload.usedCoupon
          ? { ...state.coupon, userCoupon: null }
          : state.coupon
      };
      
    case 'RESET_BOOKING':
      return {
        ...state,
        booking: {
          selectedMovie: null,
          selectedTheater: null,
          selectedTime: null,
          selectedSeats: [],
          totalPrice: 0
        }
      };
      
    default:
      return state;
  }
}

export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(appReducer, initialState);
  
  return (
    <AppContext.Provider value={{ state, dispatch }}>
      {children}
    </AppContext.Provider>
  );
}

export { AppContext };