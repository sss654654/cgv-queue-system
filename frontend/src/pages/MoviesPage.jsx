// src/pages/MoviesPage.jsx
import React, { useContext, useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { AppContext } from '../context/AppContext';
import { fetchMovies, enterMovieQueue } from '../api/api';
import styles from './MoviesPage.module.css';
import { THEATERS } from '../data/movies';

function MovieCard({ movie }) {
    const { dispatch } = useContext(AppContext);
    const navigate = useNavigate();
    const [selectedTime, setSelectedTime] = useState(null);
    const [isEntering, setIsEntering] = useState(false);

    // src/pages/MoviesPage.jsx - handleEnterQueue 함수 수정

  const handleEnterQueue = async () => {
      if (!selectedTime) {
          alert('상영시간을 선택해주세요.');
          return;
      }
      setIsEntering(true);

      try {
          const result = await enterMovieQueue(movie.movieId);
          
          dispatch({ 
              type: 'SELECT_MOVIE', 
              payload: { movieId: movie.movieId, title: movie.title, poster: movie.posterUrl } 
          });
          dispatch({ type: 'SELECT_THEATER_TIME', payload: { theater: THEATERS[0], time: selectedTime } });

          // ✅ 핵심 수정: sessionId를 result에서 가져와서 URL 파라미터에 포함
          const params = new URLSearchParams({
              requestId: result.requestId,
              sessionId: result.sessionId,    // ✅ 이 부분이 누락되어서 sessionId가 undefined였음
              movieId: movie.movieId
          });

          console.log('🚀 생성된 URL 파라미터:', {
              requestId: result.requestId,
              sessionId: result.sessionId,
              movieId: movie.movieId
          });

          if (result.status === 'SUCCESS') {
              navigate(`/seats?${params.toString()}`);
          } else if (result.status === 'QUEUED') {
              navigate(`/wait?${params.toString()}`, {
                  state: {
                      initialRank: result.myRank,
                      initialTotal: result.totalWaiting,
                      movieTitle: movie.title
                  },
              });
          }
      } catch (err) {
          console.error('❌ 대기열 진입 오류:', err);
          alert(`입장 처리 중 오류가 발생했습니다: ${err.message}`);
      } finally {
          setIsEntering(false);
      }
  };
    
    return (
        <div className={styles.movieCard}>
            <div className={styles.moviePoster}>
                <img src={movie.posterUrl || 'https://via.placeholder.com/200x280'} alt={movie.title}/>
                <div className={styles.movieRating}>{movie.ageRating}</div>
            </div>
            <div className={styles.movieInfo}>
                <h3>{movie.title}</h3>
                <p className={styles.genre}>{movie.genre} • {movie.durationInMinutes}분</p>
                <div className={styles.showTimes}>
                    <h4>상영시간</h4>
                    <div className={styles.timeSlots}>
                        {(movie.showtimes || []).map((time, index) => (
                            <button key={index}
                                className={`${styles.timeSlot} ${selectedTime === time ? styles.selected : ''}`}
                                onClick={() => setSelectedTime(time)}>
                                {time}
                            </button>
                        ))}
                    </div>
                    <button className={styles.selectButton} onClick={handleEnterQueue} disabled={!selectedTime || isEntering}>
                        {isEntering ? '처리 중...' : '좌석 선택하기'}
                    </button>
                </div>
            </div>
        </div>
    );
}

export default function MoviesPage() {
    const [movies, setMovies] = useState([]);
    const [isLoading, setIsLoading] = useState(true);

    useEffect(() => {
        fetchMovies()
            .then(data => setMovies(data))
            .catch(err => console.error(err))
            .finally(() => setIsLoading(false));
    }, []);

    if (isLoading) return <div>로딩 중...</div>;

    return (
        <div className={styles.container}>
            <header className={styles.header}><h1>현재 상영작</h1></header>
            <div className={styles.content}>
                <div className={styles.movieGrid}>
                    {movies.map(movie => <MovieCard key={movie.movieId} movie={movie} />)}
                </div>
            </div>
        </div>
    );
}