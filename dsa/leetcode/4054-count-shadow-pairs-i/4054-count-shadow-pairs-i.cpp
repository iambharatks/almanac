class Solution {
public:
    long long shadowPairs(vector<int>& nums) {
        stack<pair<int,int>> st;
        long long res = 0;
        for(int i : nums){
            while(!st.empty() && st.top().first > i){
                st.pop();
            }
            
            if(!st.empty()){
                res += st.size();
                if(st.top().first == i)
                    res -= st.top().second;
            }   
            if(!st.empty() && st.top().first == i)
                st.push({i,st.top().second+1});
            else st.push({i,1});
        }
        return res;
    }
};
