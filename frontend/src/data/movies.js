// S3 URL 대신 로컬 public 폴더 경로 사용
export const MOVIES = [
  {
    id: 1,
    title: "아바타 3",
    poster: "/posters/avatar3.jpg", // 로컬 경로
    genre: "SF/액션",
    duration: 180,
    rating: "12세 이상",
    description: "판도라의 새로운 모험이 시작됩니다.",
    showTimes: ["10:00", "13:30", "17:00", "20:30"]
  },
  {
    id: 2,
    title: "스파이더맨: 뉴 유니버스 2",
    poster: "/posters/spiderman2.jpg", // 로컬 경로
    genre: "애니메이션/액션",
    duration: 120,
    rating: "12세 이상",
    description: "멀티버스를 넘나드는 스파이더맨들의 활약",
    showTimes: ["09:30", "12:00", "14:30", "19:00", "21:30"]
  },
  {
    id: 3,
    title: "탑건: 매버릭 2",
    poster: "/posters/topgun2.jpg", // 로컬 경로
    genre: "액션/드라마",
    duration: 130,
    rating: "12세 이상",
    description: "전설의 파일럿 매버릭의 새로운 미션",
    showTimes: ["11:00", "14:00", "17:30", "20:00"]
  },
  {
    id: 4,
    title: "인터스텔라 리마스터",
    poster: "/posters/interstellar.jpg", // 로컬 경로
    genre: "SF/드라마",
    duration: 169,
    rating: "12세 이상",
    description: "우주 너머의 감동이 다시 펼쳐집니다",
    showTimes: ["10:30", "15:00", "19:30"]
  }
];

export const THEATERS = [
  { id: 1, name: "CGV 강남", location: "강남구" },
  { id: 2, name: "CGV 홍대", location: "마포구" },
  { id: 3, name: "CGV 잠실", location: "송파구" }
];

export const SEAT_LAYOUT = {
  rows: ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'],
  seatsPerRow: 14,
  aisles: [5, 10], // 통로 위치
};